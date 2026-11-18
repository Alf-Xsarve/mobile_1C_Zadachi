using System.Text.Json.Serialization;
using Npgsql;

namespace MobileTasks.Api.Data;

public sealed record DatabaseResult(string Status, string Database,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Code = null);

public sealed class DatabaseProbe(Database database, ILogger<DatabaseProbe> logger)
{
    public async Task<DatabaseResult> CheckAsync(CancellationToken requestAborted)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(requestAborted);
        deadline.CancelAfter(TimeSpan.FromSeconds(7));
        try
        {
            await using var command = database.Source.CreateCommand("SELECT 1");
            var answer = await command.ExecuteScalarAsync(deadline.Token);
            return answer is int value && value == 1
                ? new("ok", "connected") : new("error", "unavailable", "unexpected_database_response");
        }
        catch (PostgresException error)
        {
            logger.LogWarning("Database check failed; SQLSTATE={SqlState}", error.SqlState);
            var code = error.SqlState switch
            {
                "28P01" => "database_authentication_failed",
                "28000" => "database_access_denied",
                "3D000" => "database_not_found",
                "42501" => "database_permission_denied",
                _ => "database_unavailable"
            };
            return new("error", "unavailable", code);
        }
        catch (OperationCanceledException) when (!requestAborted.IsCancellationRequested)
        { return new("error", "unavailable", "database_timeout"); }
        catch (NpgsqlException)
        { return new("error", "unavailable", "database_connection_failed"); }
        catch (DatabaseNotConfiguredException error)
        { return new("error", "not_configured", error.Code); }
    }
}
