using MobileTasks.Api.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Net.Http.Json;
namespace MobileTasks.Api.Api;

public sealed record DeviceRequest(Guid Id, string Token, string[]? Sources = null);
public sealed record OneCEvent(Guid TaskId, string Source);
public sealed class PushService(Database db, IConfiguration config, ILogger<PushService> logger) : BackgroundService
{
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private string bearer = ""; private DateTime until; private string project = "";
    private static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private async Task Authenticate(CancellationToken ct)
    {
        if (DateTime.UtcNow < until) return;
        using var doc = JsonDocument.Parse(await File.ReadAllTextAsync(config["Push:ServiceAccountFile"]!, ct));
        var root = doc.RootElement; project = root.GetProperty("project_id").GetString()!;
        if (!System.Text.RegularExpressions.Regex.IsMatch(project, "^[a-z0-9-]+$")) throw new InvalidOperationException();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"));
        var payload = B64(JsonSerializer.SerializeToUtf8Bytes(new { iss = root.GetProperty("client_email").GetString(), scope = "https://www.googleapis.com/auth/firebase.messaging", aud = "https://oauth2.googleapis.com/token", iat = now, exp = now + 3600 }));
        using var rsa = RSA.Create(); rsa.ImportFromPem(root.GetProperty("private_key").GetString());
        var signed = header + "." + payload; var assertion = signed + "." + B64(rsa.SignData(Encoding.UTF8.GetBytes(signed), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
        using var response = await http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(new Dictionary<string, string> { { "grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer" }, { "assertion", assertion } }), ct);
        response.EnsureSuccessStatusCode(); using var token = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct)); bearer = token.RootElement.GetProperty("access_token").GetString()!; until = DateTime.UtcNow.AddMinutes(50);
    }
    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!string.IsNullOrWhiteSpace(config["Push:ServiceAccountFile"])) await Deliver(ct);
            }
            catch (Exception e) when (e is not OperationCanceledException) { logger.LogWarning("Push delivery delayed ({Type})", e.GetType().Name); }
            try { await Task.Delay(TimeSpan.FromSeconds(5), ct); } catch (OperationCanceledException) { break; }
        }
    }
    private async Task Deliver(CancellationToken ct)
    {
        await using var conn = await db.Source.OpenConnectionAsync(ct); await using var tx = await conn.BeginTransactionAsync(ct);
        await using (var expired = new Npgsql.NpgsqlCommand("DELETE FROM mobile_tasks.push_queue WHERE attempts>=12 OR created_at<now()-interval '1 day'", conn, tx)) await expired.ExecuteNonQueryAsync(ct);
        await using var cmd = new Npgsql.NpgsqlCommand("SELECT id,actor,kind,source FROM mobile_tasks.push_queue WHERE next_at<=now() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", conn, tx);
        long id; Guid? actor; string kind, source;
        await using (var r = await cmd.ExecuteReaderAsync(ct)) { if (!await r.ReadAsync(ct)) return; id = r.GetInt64(0); actor = r.IsDBNull(1) ? null : r.GetGuid(1); kind = r.GetString(2); source = r.GetString(3); }
        await Authenticate(ct);
        var devices = new List<(Guid, string)>();
        await using (var list = new Npgsql.NpgsqlCommand("SELECT user_id,token FROM mobile_tasks.devices WHERE expires_at>now() AND ($1::uuid IS NULL OR user_id<>$1) AND ($3='common' OR sources @> jsonb_build_array($2::text))", conn, tx))
        {
            list.Parameters.Add(new Npgsql.NpgsqlParameter { NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Uuid, Value = (object?)actor ?? DBNull.Value }); list.Parameters.AddWithValue(source.TrimEnd('/')); list.Parameters.AddWithValue(kind); await using var r = await list.ExecuteReaderAsync(ct); while (await r.ReadAsync(ct)) devices.Add((r.GetGuid(0), r.GetString(1)));
        }
        var failed = false;
        foreach (var device in devices)
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, $"https://fcm.googleapis.com/v1/projects/{project}/messages:send"); req.Headers.Authorization = new("Bearer", bearer);
            req.Content = JsonContent.Create(new { message = new { token = device.Item2, data = new Dictionary<string, string> { { "eventId", id.ToString() }, { "kind", kind }, { "source", source }, { "userId", device.Item1.ToString() } }, android = new { priority = "high", ttl = "3600s" } } });
            using var res = await http.SendAsync(req, ct);
            if (res.StatusCode == System.Net.HttpStatusCode.NotFound && (await res.Content.ReadAsStringAsync(ct)).Contains("UNREGISTERED", StringComparison.Ordinal)) { await using var remove = new Npgsql.NpgsqlCommand("DELETE FROM mobile_tasks.devices WHERE token=$1", conn, tx); remove.Parameters.AddWithValue(device.Item2); await remove.ExecuteNonQueryAsync(ct); }
            else if (!res.IsSuccessStatusCode) { failed = true; if (res.StatusCode == System.Net.HttpStatusCode.Unauthorized) until = DateTime.MinValue; }
        }
        await using var update = new Npgsql.NpgsqlCommand(failed ? "UPDATE mobile_tasks.push_queue SET attempts=attempts+1,next_at=now()+interval '5 minutes' WHERE id=$1" : "DELETE FROM mobile_tasks.push_queue WHERE id=$1", conn, tx); update.Parameters.AddWithValue(id); await update.ExecuteNonQueryAsync(ct);
        await using var clean = new Npgsql.NpgsqlCommand("DELETE FROM mobile_tasks.push_queue WHERE attempts>=12 OR created_at<now()-interval '1 day'", conn, tx); await clean.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }
    public static void Map(WebApplication app)
    {
        app.MapPost("/api/devices", async (DeviceRequest request, HttpContext ctx, Database db, CancellationToken ct) =>
        {
            if (request.Id == Guid.Empty || string.IsNullOrWhiteSpace(request.Token) || request.Token.Length > 4096) throw new ApiError(400, "invalid_device", "Неверное устройство.");
            var sources = request.Sources ?? [];
            if (sources.Length > 100 || sources.Any(s => s is null || s.Length > 2048 || !Uri.TryCreate(s, UriKind.Absolute, out var u) || u.Scheme is not ("http" or "https") || u.UserInfo != "" || u.Query != "")) throw new ApiError(400, "invalid_sources", "Неверные источники.");
            var user = Guid.Parse(ctx.User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value);
            await using var cmd = db.Source.CreateCommand("INSERT INTO mobile_tasks.devices(id,user_id,token,sources) VALUES($1,$2,$3,$4) ON CONFLICT(token) DO UPDATE SET user_id=$2,sources=$4,expires_at=now()+interval '30 days'"); cmd.Parameters.AddWithValue(request.Id); cmd.Parameters.AddWithValue(user); cmd.Parameters.AddWithValue(request.Token); cmd.Parameters.Add(new Npgsql.NpgsqlParameter { NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Jsonb, Value = JsonSerializer.Serialize(sources.Select(s => s.TrimEnd('/'))) }); await cmd.ExecuteNonQueryAsync(ct); return Results.Ok(new { status = "ok" });
        }).RequireAuthorization();
        app.MapPost("/api/integrations/1c/new-task", async (OneCEvent request, HttpContext ctx, Database db, IConfiguration cfg, CancellationToken ct) =>
        {
            var secret = cfg["Push:OneCSecret"];
            if (string.IsNullOrEmpty(secret) || secret.Length < 32) throw new ApiError(503, "push_not_configured", "Интеграция не настроена.");
            var actual = ctx.Request.Headers["X-OneC-Key"].ToString();
            if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(Encoding.UTF8.GetBytes(secret)), SHA256.HashData(Encoding.UTF8.GetBytes(actual)))) throw new ApiError(401, "invalid_key", "Неверный ключ.");
            if (request.TaskId == Guid.Empty || string.IsNullOrWhiteSpace(request.Source) || request.Source.Length > 2048 || !Uri.TryCreate(request.Source, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http") || uri.UserInfo != "" || uri.Query != "") throw new ApiError(400, "invalid_source", "Неверный источник.");
            await using var cmd = db.Source.CreateCommand("INSERT INTO mobile_tasks.push_queue(kind,source,dedup) VALUES('odata',$1,$2) ON CONFLICT(dedup) DO NOTHING"); cmd.Parameters.AddWithValue(request.Source); cmd.Parameters.AddWithValue(request.Source + ":" + request.TaskId); await cmd.ExecuteNonQueryAsync(ct); return Results.Ok(new { status = "ok" });
        });
    }
}
