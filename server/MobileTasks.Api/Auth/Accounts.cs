using System.Net.Mail;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.WebUtilities;
using MobileTasks.Api.Api;
using MobileTasks.Api.Data;
using Npgsql;

namespace MobileTasks.Api.Auth;

public sealed record UserView(Guid Id, string Email, string Name);
public sealed record AuthReply(string Token, DateTimeOffset ExpiresAt, UserView User);
public sealed record RegisterRequest(string? Email, string? Name, string? Password);
public sealed record LoginRequest(string? Email, string? Password);

public sealed class Accounts(Database database, IPasswordHasher<UserView> hasher)
{
    private readonly UserView dummyUser = new(Guid.Empty, "invalid@example.invalid", "invalid");
    private readonly string dummyHash = hasher.HashPassword(new(Guid.Empty, "invalid@example.invalid", "invalid"), "random-dummy-password-for-timing");

    private static string NormalizeEmail(string? raw)
    {
        var email = raw?.Trim().ToLowerInvariant() ?? "";
        if (email.Length > 254 || !MailAddress.TryCreate(email, out var address) || address.Address != email)
            throw new ApiError(400, "invalid_email", "Укажите корректный email.");
        return email;
    }

    public async Task<AuthReply> RegisterAsync(RegisterRequest request, CancellationToken cancellationToken)
    {
        var email = NormalizeEmail(request.Email);
        var name = request.Name?.Trim() ?? "";
        if (name.Length is < 1 or > 80) throw new ApiError(400, "invalid_name", "Имя: от 1 до 80 символов.");
        if (request.Password is null || request.Password.Length is < 12 or > 256)
            throw new ApiError(400, "invalid_password", "Пароль: от 12 до 256 символов.");
        var user = new UserView(Guid.NewGuid(), email, name);
        var hash = hasher.HashPassword(user, request.Password);
        await using var connection = await database.Source.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using var insert = new NpgsqlCommand("INSERT INTO mobile_tasks.users(id,email,name,password_hash) VALUES ($1,$2,$3,$4)", connection, transaction);
        insert.Parameters.AddWithValue(user.Id);
        insert.Parameters.AddWithValue(email);
        insert.Parameters.AddWithValue(name);
        insert.Parameters.AddWithValue(hash);
        try { await insert.ExecuteNonQueryAsync(cancellationToken); }
        catch (PostgresException error) when (error.SqlState == "23505")
        { throw new ApiError(409, "email_registered", "Этот email уже зарегистрирован."); }
        var reply = await NewSessionAsync(connection, transaction, user, cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return reply;
    }

    public async Task<AuthReply> LoginAsync(LoginRequest request, CancellationToken cancellationToken)
    {
        var email = NormalizeEmail(request.Email);
        if (request.Password is null || request.Password.Length is < 1 or > 256)
            throw InvalidLogin();
        await using var connection = await database.Source.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using var find = new NpgsqlCommand("SELECT id,email,name,password_hash,failed_attempts,locked_until FROM mobile_tasks.users WHERE email=$1 FOR UPDATE", connection, transaction);
        find.Parameters.AddWithValue(email);
        UserView? user = null;
        string hash = dummyHash;
        int attempts = 0;
        DateTime? lockedUntil = null;
        await using (var reader = await find.ExecuteReaderAsync(cancellationToken))
        {
            if (await reader.ReadAsync(cancellationToken))
            {
                user = new(reader.GetGuid(0), reader.GetString(1), reader.GetString(2));
                hash = reader.GetString(3);
                attempts = reader.GetInt32(4);
                lockedUntil = reader.IsDBNull(5) ? null : reader.GetDateTime(5);
            }
        }
        if (lockedUntil > DateTime.UtcNow) throw InvalidLogin();
        if (lockedUntil is not null) attempts = 0;
        var verified = hasher.VerifyHashedPassword(user ?? dummyUser, hash, request.Password);
        if (user is null || verified == PasswordVerificationResult.Failed)
        {
            if (user is not null)
            {
                await using var failed = new NpgsqlCommand("""
                    UPDATE mobile_tasks.users SET failed_attempts=$2,
                    locked_until=CASE WHEN $2 >= 5 THEN now()+interval '15 minutes' ELSE NULL END
                    WHERE id=$1
                    """, connection, transaction);
                failed.Parameters.AddWithValue(user.Id);
                failed.Parameters.AddWithValue(attempts + 1);
                await failed.ExecuteNonQueryAsync(cancellationToken);
                await transaction.CommitAsync(cancellationToken);
            }
            throw InvalidLogin();
        }
        await using var reset = new NpgsqlCommand("UPDATE mobile_tasks.users SET failed_attempts=0,locked_until=NULL,password_hash=$2 WHERE id=$1", connection, transaction);
        reset.Parameters.AddWithValue(user.Id);
        reset.Parameters.AddWithValue(verified == PasswordVerificationResult.SuccessRehashNeeded ? hasher.HashPassword(user, request.Password) : hash);
        await reset.ExecuteNonQueryAsync(cancellationToken);
        var reply = await NewSessionAsync(connection, transaction, user, cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return reply;
    }

    private static ApiError InvalidLogin() => new(401, "invalid_credentials", "Неверный email или пароль либо вход временно заблокирован.");
    public static string TokenHash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private static async Task<AuthReply> NewSessionAsync(NpgsqlConnection connection, NpgsqlTransaction transaction, UserView user, CancellationToken cancellationToken)
    {
        var token = WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
        var expiry = DateTimeOffset.UtcNow.AddDays(30);
        // Remove expired sessions for this account without touching other users.
        await using var cleanup = new NpgsqlCommand("DELETE FROM mobile_tasks.sessions WHERE user_id=$1 AND expires_at <= now()", connection, transaction);
        cleanup.Parameters.AddWithValue(user.Id);
        await cleanup.ExecuteNonQueryAsync(cancellationToken);
        await using var insert = new NpgsqlCommand("INSERT INTO mobile_tasks.sessions(token_hash,user_id,expires_at) VALUES ($2,$1,$3)", connection, transaction);
        insert.Parameters.AddWithValue(user.Id);
        insert.Parameters.AddWithValue(TokenHash(token));
        insert.Parameters.AddWithValue(expiry);
        await insert.ExecuteNonQueryAsync(cancellationToken);
        return new(token, expiry, user);
    }

    public async Task<UserView?> ResolveAsync(string token, CancellationToken cancellationToken)
    {
        if (token.Length != 43 || token.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) return null;
        await using var command = database.Source.CreateCommand("""
            SELECT u.id,u.email,u.name FROM mobile_tasks.sessions s
            JOIN mobile_tasks.users u ON u.id=s.user_id
            WHERE s.token_hash=$1 AND s.expires_at > now()
            """);
        command.Parameters.AddWithValue(TokenHash(token));
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        return await reader.ReadAsync(cancellationToken) ? new(reader.GetGuid(0), reader.GetString(1), reader.GetString(2)) : null;
    }

    public async Task LogoutAsync(string token, CancellationToken cancellationToken)
    {
        await using var command = database.Source.CreateCommand("DELETE FROM mobile_tasks.sessions WHERE token_hash=$1");
        command.Parameters.AddWithValue(TokenHash(token));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }
}
