using Npgsql;

namespace MobileTasks.Api.Data;

public sealed class Database(IConfiguration configuration) : IAsyncDisposable
{
    private readonly Lazy<NpgsqlDataSource?> source = new(() => Build(configuration));
    public NpgsqlDataSource Source => source.Value ?? throw new DatabaseNotConfiguredException();

    private static NpgsqlDataSource? Build(IConfiguration configuration)
    {
        var settings = configuration.GetSection("Database");
        if (new[] { "Host", "Name", "Username", "Password" }.Any(key => string.IsNullOrWhiteSpace(settings[key])))
            return null;
        if (!int.TryParse(settings["Port"], out var port) || port is < 1 or > 65535)
            throw new DatabaseNotConfiguredException("database_configuration_invalid");
        var connection = new NpgsqlConnectionStringBuilder
        {
            Host = settings["Host"], Port = port, Database = settings["Name"],
            Username = settings["Username"], Password = settings["Password"],
            Timeout = 5, CommandTimeout = 10, MaxPoolSize = 10,
            IncludeErrorDetail = false, ApplicationName = "MobileTasks.Api"
        };
        return NpgsqlDataSource.Create(connection.ConnectionString);
    }

    public ValueTask DisposeAsync() => source.IsValueCreated && source.Value is { } dataSource
        ? dataSource.DisposeAsync() : ValueTask.CompletedTask;
}

public sealed class DatabaseNotConfiguredException(string code = "database_not_configured") : Exception
{
    public string Code { get; } = code;
}
