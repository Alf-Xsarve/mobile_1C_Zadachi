using System.Security.Claims;
using MobileTasks.Api.Auth;
using MobileTasks.Api.Tasks;

namespace MobileTasks.Api.Api;

public static class Routes
{
    private static Guid Actor(HttpContext context) => Guid.Parse(context.User.FindFirstValue(ClaimTypes.NameIdentifier)!);
    public static void MapTaskApi(this WebApplication app)
    {
        var auth = app.MapGroup("/api/auth").RequireRateLimiting("auth");
        auth.MapPost("/register", async (RegisterRequest request, Accounts accounts, CancellationToken token) =>
            Results.Json(await accounts.RegisterAsync(request, token), statusCode: 201));
        auth.MapPost("/login", async (LoginRequest request, Accounts accounts, CancellationToken token) =>
            Results.Ok(await accounts.LoginAsync(request, token)));
        app.MapGet("/api/auth/me", (HttpContext context) => Results.Ok(new UserView(Actor(context),
            context.User.FindFirstValue(ClaimTypes.Email)!, context.User.Identity!.Name!))).RequireAuthorization();
        app.MapPost("/api/auth/logout", async (HttpContext context, Accounts accounts, CancellationToken token) =>
        {
            await accounts.LogoutAsync(SessionAuthentication.Token(context)!, token);
            return Results.NoContent();
        }).RequireAuthorization();

        app.MapFiles();
        app.MapGet("/api/users", async (MobileTasks.Api.Data.Database db, CancellationToken ct) =>
        {
            await using var cmd = db.Source.CreateCommand("SELECT id,name FROM mobile_tasks.users ORDER BY name,id");
            await using var reader = await cmd.ExecuteReaderAsync(ct); var users = new List<object>();
            while (await reader.ReadAsync(ct)) users.Add(new { id = reader.GetGuid(0), name = reader.GetString(1) });
            return Results.Ok(new { items = users });
        }).RequireAuthorization();
        var tasks = app.MapGroup("/api/tasks").RequireAuthorization();
        tasks.MapPost("/{id:guid}/action", async (Guid id, ActionRequest request, HttpContext ctx, TaskStore store, CancellationToken ct) => Results.Ok(await store.ActionAsync(id, Actor(ctx), request, ct)));
        tasks.MapGet("/{id:guid}/links", async (Guid id, LinkStore store, CancellationToken token) =>
            Results.Ok(new { items = await store.ListAsync(id, token) }));
        tasks.MapPost("/{id:guid}/links", async (Guid id, LinksRequest request, HttpContext context, LinkStore store, CancellationToken token) =>
        {
            await store.AddAsync(id, Actor(context), request, token);
            return Results.Ok(new { status = "ok" });
        });
        tasks.MapGet("/", async (HttpContext context, TaskStore store, CancellationToken token,
            string? status = null, bool mine = false, int limit = 50, string? cursor = null) =>
            Results.Ok(await store.ListAsync(Actor(context), status, mine, limit, cursor, token)));
        tasks.MapGet("/{id:guid}", async (Guid id, TaskStore store, CancellationToken token) =>
            Results.Ok(await store.GetAsync(id, token)));
        tasks.MapPost("/", async (CreateTaskRequest request, HttpContext context, TaskStore store, CancellationToken token) =>
        {
            var result = await store.CreateAsync(Actor(context), request, token);
            return Results.Json(result.Task, statusCode: result.Created ? 201 : 200);
        });
        tasks.MapPost("/{id:guid}/claim", async (Guid id, HttpContext context, TaskStore store, CancellationToken token) =>
            Results.Ok(await store.TransitionAsync(id, Actor(context), context.User.FindFirstValue(ClaimTypes.Email)!, false, token)));
        tasks.MapPost("/{id:guid}/complete", async (Guid id, HttpContext context, TaskStore store, CancellationToken token) =>
            Results.Ok(await store.TransitionAsync(id, Actor(context), context.User.FindFirstValue(ClaimTypes.Email)!, true, token)));
        tasks.MapGet("/{id:guid}/events", async (Guid id, TaskStore store, CancellationToken token, int limit = 50, string? cursor = null) =>
            Results.Ok(await store.EventsAsync(id, limit, cursor, token)));
        tasks.MapPost("/{id:guid}/comments", async (Guid id, CommentRequest request, HttpContext context, TaskStore store, CancellationToken token) =>
            Results.Ok(new { id = await store.CommentAsync(id, Actor(context), request, token) }));
    }
}
