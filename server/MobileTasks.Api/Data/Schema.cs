using Npgsql;

namespace MobileTasks.Api.Data;

public sealed class Schema(Database database, IConfiguration configuration)
{
    public const int CurrentVersion = 2;
    public async Task<int> VersionAsync(CancellationToken cancellationToken)
    {
        try
        {
            await using var command = database.Source.CreateCommand("SELECT COALESCE(MAX(version),0) FROM mobile_tasks.schema_migrations");
            return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken));
        }
        catch (PostgresException error) when (error.SqlState is "42P01" or "3F000") { return 0; }
    }

    public async Task MigrateAsync(CancellationToken cancellationToken)
    {
        // A typo must not cause application tables to be created in a 1C database.
        if (configuration["Database:Name"] != "mobile_tasks")
            throw new InvalidOperationException("Migration requires the dedicated mobile_tasks database.");
        await using var connection = await database.Source.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using (var command = new NpgsqlCommand("""
            SELECT pg_advisory_xact_lock(782361450);
            CREATE SCHEMA IF NOT EXISTS mobile_tasks;
            CREATE TABLE IF NOT EXISTS mobile_tasks.schema_migrations (
                version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
            );
            """, connection, transaction))
            await command.ExecuteNonQueryAsync(cancellationToken);
        await using var versionCommand = new NpgsqlCommand("SELECT COALESCE(MAX(version),0) FROM mobile_tasks.schema_migrations", connection, transaction);
        var version = Convert.ToInt32(await versionCommand.ExecuteScalarAsync(cancellationToken));
        if (version > CurrentVersion) throw new InvalidOperationException("Database schema is newer than this API.");
        for (var next = version + 1; next <= CurrentVersion; next++)
        {
            var filename = next == 1 ? "001_initial.sql" : "002_links.sql";
            using var resource = typeof(Schema).Assembly.GetManifestResourceStream("MobileTasks.Api.Data.Migrations." + filename)
                ?? throw new InvalidOperationException("Missing migration resource.");
            using var reader = new StreamReader(resource);
            var sql = await reader.ReadToEndAsync(cancellationToken);
            await using var migration = new NpgsqlCommand(sql, connection, transaction);
            await migration.ExecuteNonQueryAsync(cancellationToken);
            await using var record = new NpgsqlCommand("INSERT INTO mobile_tasks.schema_migrations(version) VALUES ($1)", connection, transaction);
            record.Parameters.AddWithValue(next);
            await record.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
    }
}
