using System.Net;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.RateLimiting;
using MobileTasks.Api.Api;
using MobileTasks.Api.Auth;
using MobileTasks.Api.Data;
using MobileTasks.Api.Tasks;
using Npgsql;

var migrationMode = args.Contains("--migrate", StringComparer.Ordinal);
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args.Where(arg => arg != "--migrate").ToArray(),
    ContentRootPath = AppContext.BaseDirectory
});
var databaseFile = Environment.GetEnvironmentVariable("MOBILETASKS_CONFIG_FILE")
    ?? Path.GetFullPath(Path.Combine(builder.Environment.ContentRootPath, "..", "MobileTasksConfig", "database.json"));
builder.Configuration.AddJsonFile(databaseFile, optional: true, reloadOnChange: false);
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 65536);
builder.Services.Configure<IISServerOptions>(options => options.MaxRequestBodySize = 65536);
builder.Services.AddSingleton<Database>();
builder.Services.AddSingleton<DatabaseProbe>();
builder.Services.AddSingleton<Schema>();
builder.Services.Configure<PasswordHasherOptions>(options => options.IterationCount = 210000);
builder.Services.AddSingleton<IPasswordHasher<UserView>, PasswordHasher<UserView>>();
builder.Services.AddSingleton<Accounts>();
builder.Services.AddSingleton<TaskStore>();
builder.Services.AddSingleton<LinkStore>();
builder.Services.AddAuthentication("Session").AddScheme<AuthenticationSchemeOptions, SessionAuthentication>("Session", null);
builder.Services.AddAuthorization();
builder.Services.AddRateLimiter(options =>
{
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(context =>
        context.Request.Path.StartsWithSegments("/api")
            ? RateLimitPartition.GetFixedWindowLimiter(context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
                _ => new FixedWindowRateLimiterOptions { PermitLimit = 300, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 })
            : RateLimitPartition.GetNoLimiter("health"));
    options.AddPolicy("auth", context => RateLimitPartition.GetFixedWindowLimiter(
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 30, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
    options.OnRejected = async (context, token) =>
    {
        context.HttpContext.Response.StatusCode = 429;
        context.HttpContext.Response.Headers.RetryAfter = "60";
        await context.HttpContext.Response.WriteAsJsonAsync(new { code = "rate_limited", message = "Слишком много запросов. Повторите позже." }, token);
    };
});
var app = builder.Build();

if (migrationMode)
{
    try
    {
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        await app.Services.GetRequiredService<Schema>().MigrateAsync(deadline.Token);
        Console.WriteLine("Migration completed. Schema version: " + Schema.CurrentVersion);
    }
    catch (Exception error) when (error is NpgsqlException or DatabaseNotConfiguredException or InvalidOperationException or OperationCanceledException)
    {
        Console.Error.WriteLine("Migration failed. Code: " + (error is PostgresException pg ? pg.SqlState : error.GetType().Name));
        Environment.ExitCode = 1;
    }
    await app.DisposeAsync();
    return;
}

static bool IsLocal(HttpContext context)
{
    var remote = context.Connection.RemoteIpAddress;
    return remote is not null && IPAddress.IsLoopback(remote.IsIPv4MappedToIPv6 ? remote.MapToIPv4() : remote);
}

app.Use(async (context, next) =>
{
    context.Response.Headers.CacheControl = "no-store";
    try
    {
        if (context.Request.Path.StartsWithSegments("/api"))
        {
            if (!context.Request.IsHttps && !IsLocal(context))
                throw new ApiError(400, "https_required", "Для подключения приложения требуется HTTPS.");
            if (await context.RequestServices.GetRequiredService<Schema>().VersionAsync(context.RequestAborted) != Schema.CurrentVersion)
                throw new ApiError(503, "schema_not_ready", "Требуется обновление схемы базы приложения.");
        }
        await next(context);
    }
    catch (ApiError error) when (!context.Response.HasStarted)
    {
        context.Response.StatusCode = error.Status;
        await context.Response.WriteAsJsonAsync(new { code = error.Code, message = error.Message });
    }
    catch (DatabaseNotConfiguredException error) when (!context.Response.HasStarted)
    {
        context.Response.StatusCode = 503;
        await context.Response.WriteAsJsonAsync(new { code = error.Code, message = "База приложения не настроена." });
    }
    catch (NpgsqlException error) when (!context.Response.HasStarted)
    {
        app.Logger.LogWarning("Database operation failed; SQLSTATE={SqlState}", error is PostgresException pg ? pg.SqlState : "connection");
        context.Response.StatusCode = 503;
        await context.Response.WriteAsJsonAsync(new { code = "database_unavailable", message = "База временно недоступна." });
    }
});
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/", () => Results.Ok(new { service = "MobileTasks.Api", stage = "tasks-api", schemaVersion = Schema.CurrentVersion }));
app.MapGet("/health/live", () => Results.Ok(new { status = "ok", service = "MobileTasks.Api", stage = "tasks-api" }));
app.MapGet("/health/database", async (HttpContext context, DatabaseProbe probe) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var result = await probe.CheckAsync(context.RequestAborted);
    return Results.Json(result, statusCode: result.Status == "ok" ? 200 : 503);
});
app.MapGet("/health/schema", async (HttpContext context, Schema schema) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var version = await schema.VersionAsync(context.RequestAborted);
    return Results.Json(new { status = version == Schema.CurrentVersion ? "ok" : "migration_required",
        schemaVersion = version, requiredVersion = Schema.CurrentVersion }, statusCode: version == Schema.CurrentVersion ? 200 : 503);
});
app.MapTaskApi();
app.Run();
