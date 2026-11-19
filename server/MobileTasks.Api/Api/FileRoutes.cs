using MobileTasks.Api.Data;
using MobileTasks.Api.Tasks;
namespace MobileTasks.Api.Api;

public static class FileRoutes
{
    public static void MapFiles(this WebApplication app)
    {
        var group = app.MapGroup("/api/tasks/{taskId:guid}/files").RequireAuthorization();
        group.MapGet("/", async (Guid taskId, Database db, TaskStore tasks, CancellationToken ct) =>
        {
            await tasks.GetAsync(taskId, ct);
            await using var cmd = db.Source.CreateCommand("SELECT id,name,octet_length(data) FROM mobile_tasks.task_files WHERE task_id=$1 ORDER BY created_at,id"); cmd.Parameters.AddWithValue(taskId);
            var rows = new List<object>(); await using var reader = await cmd.ExecuteReaderAsync(ct);
            while (await reader.ReadAsync(ct)) rows.Add(new { id = reader.GetGuid(0), name = reader.GetString(1), size = reader.GetInt32(2) });
            return Results.Ok(new { items = rows });
        });
        group.MapPut("/{id:guid}", async (Guid taskId, Guid id, string name, HttpContext ctx, Database db, TaskStore tasks, CancellationToken ct) =>
        {
            if (id == Guid.Empty || string.IsNullOrWhiteSpace(name) || name.Length > 200 || name.Any(char.IsControl) || name.Contains('/') || name.Contains('\\')) throw new ApiError(400, "invalid_filename", "Некорректное имя файла.");
            await tasks.GetAsync(taskId, ct);
            using var buffer = new MemoryStream(); var chunk = new byte[65536]; int read;
            while ((read = await ctx.Request.Body.ReadAsync(chunk, ct)) > 0) { if (buffer.Length + read > 10485760) throw new ApiError(413, "file_too_large", "Максимум 10 МБ."); await buffer.WriteAsync(chunk.AsMemory(0, read), ct); }
            if (buffer.Length == 0) throw new ApiError(400, "empty_file", "Файл пуст.");
            var actor = Guid.Parse(ctx.User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value);
            await using var cmd = db.Source.CreateCommand("INSERT INTO mobile_tasks.task_files(id,task_id,author_id,name,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO UPDATE SET name=mobile_tasks.task_files.name WHERE mobile_tasks.task_files.task_id=$2 AND mobile_tasks.task_files.author_id=$3 AND mobile_tasks.task_files.name=$4 AND mobile_tasks.task_files.data=$5");
            cmd.Parameters.AddWithValue(id); cmd.Parameters.AddWithValue(taskId); cmd.Parameters.AddWithValue(actor); cmd.Parameters.AddWithValue(name); cmd.Parameters.AddWithValue(buffer.ToArray());
            if (await cmd.ExecuteNonQueryAsync(ct) != 1) throw new ApiError(409, "file_conflict", "Этот ID уже занят другим файлом.");
            return Results.Ok(new { status = "ok" });
        });
        group.MapGet("/{id:guid}", async (Guid taskId, Guid id, Database db, CancellationToken ct) =>
        {
            await using var cmd = db.Source.CreateCommand("SELECT name,data FROM mobile_tasks.task_files WHERE task_id=$1 AND id=$2"); cmd.Parameters.AddWithValue(taskId); cmd.Parameters.AddWithValue(id);
            await using var reader = await cmd.ExecuteReaderAsync(ct); if (!await reader.ReadAsync(ct)) return Results.NotFound();
            return Results.File(reader.GetFieldValue<byte[]>(1), "application/octet-stream", reader.GetString(0));
        });
    }
}
