using MobileTasks.Api.Api;
using MobileTasks.Api.Data;
using Npgsql;

namespace MobileTasks.Api.Tasks;

public sealed record LinkInput(Guid Id, string? Name, string? Url, string? Kind);
public sealed record LinksRequest(IReadOnlyList<LinkInput>? Items);
public sealed record LinkView(Guid Id, Guid TaskId, string Name, string Url, string Kind,
    Guid AuthorId, string AuthorName, DateTimeOffset CreatedAt);

public sealed class LinkStore(Database database, TaskStore tasks)
{
    public async Task<IReadOnlyList<LinkView>> ListAsync(Guid taskId, CancellationToken token)
    {
        await tasks.GetAsync(taskId, token);
        await using var command = database.Source.CreateCommand("""
            SELECT l.id,l.name,l.url,l.kind,l.author_id,u.name,l.created_at
            FROM mobile_tasks.task_links l JOIN mobile_tasks.users u ON u.id=l.author_id
            WHERE l.task_id=$1 ORDER BY l.created_at,l.id
            """);
        command.Parameters.AddWithValue(taskId);
        var rows = new List<LinkView>();
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token)) rows.Add(new(reader.GetGuid(0),taskId,reader.GetString(1),
            reader.GetString(2),reader.GetString(3),reader.GetGuid(4),reader.GetString(5),reader.GetDateTime(6)));
        return rows;
    }

    private static void Validate(LinkInput item)
    {
        if (item.Id == Guid.Empty || string.IsNullOrWhiteSpace(item.Name) || item.Name.Length > 160 ||
            item.Kind is not ("web" or "file" or "image" or "whatsapp" or "telegram"))
            throw new ApiError(400,"invalid_link","Проверьте название и вид ссылки.");
        if (string.IsNullOrWhiteSpace(item.Url) || item.Url.Length > 4096 || item.Url.Any(char.IsControl) ||
            !Uri.TryCreate(item.Url,UriKind.Absolute,out var uri) || uri.Scheme is not ("http" or "https") ||
            string.IsNullOrEmpty(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo))
            throw new ApiError(400,"invalid_url","Нужна ссылка HTTP или HTTPS без логина и пароля.");
        if (item.Kind is "whatsapp" or "telegram")
        {
            var host = uri.IdnHost.ToLowerInvariant();
            var valid = item.Kind == "whatsapp"
                ? host == "wa.me" || host == "whatsapp.com" || host.EndsWith(".whatsapp.com",StringComparison.Ordinal)
                : host == "t.me" || host == "telegram.me" || host.EndsWith(".t.me",StringComparison.Ordinal);
            if (!valid || uri.Scheme != "https") throw new ApiError(400,"invalid_contact","Нужна HTTPS-ссылка WhatsApp или Telegram.");
        }
    }

    public async Task AddAsync(Guid taskId, Guid actor, LinksRequest request, CancellationToken token)
    {
        if (request.Items is null || request.Items.Count is < 1 or > 20 || request.Items.Select(x=>x.Id).Distinct().Count() != request.Items.Count)
            throw new ApiError(400,"invalid_links","Отправьте от 1 до 20 ссылок с разными UUID.");
        foreach(var item in request.Items) Validate(item);
        await tasks.GetAsync(taskId,token);
        await using var connection = await database.Source.OpenConnectionAsync(token);
        await using var transaction = await connection.BeginTransactionAsync(token);
        // Serialize additions to one task; retries keep stable UUIDs and all-or-nothing behavior.
        await using (var taskLock = new NpgsqlCommand("SELECT id FROM mobile_tasks.tasks WHERE id=$1 FOR UPDATE",connection,transaction))
        {
            taskLock.Parameters.AddWithValue(taskId);
            await taskLock.ExecuteScalarAsync(token);
        }
        foreach(var item in request.Items.OrderBy(x=>x.Id))
        {
            await using var insert = new NpgsqlCommand("""
                INSERT INTO mobile_tasks.task_links(id,task_id,author_id,name,url,kind)
                VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(id) DO NOTHING
                """,connection,transaction);
            insert.Parameters.AddWithValue(item.Id); insert.Parameters.AddWithValue(taskId);
            insert.Parameters.AddWithValue(actor); insert.Parameters.AddWithValue(item.Name!);
            insert.Parameters.AddWithValue(item.Url!); insert.Parameters.AddWithValue(item.Kind!);
            if(await insert.ExecuteNonQueryAsync(token) == 0)
            {
                await using var existing = new NpgsqlCommand("SELECT task_id,author_id,name,url,kind FROM mobile_tasks.task_links WHERE id=$1",connection,transaction);
                existing.Parameters.AddWithValue(item.Id);
                await using var reader = await existing.ExecuteReaderAsync(token);
                if(!await reader.ReadAsync(token) || reader.GetGuid(0)!=taskId || reader.GetGuid(1)!=actor ||
                    reader.GetString(2)!=item.Name || reader.GetString(3)!=item.Url || reader.GetString(4)!=item.Kind)
                    throw new ApiError(409,"link_id_conflict","Этот UUID уже использован для другой ссылки.");
            }
        }
        await transaction.CommitAsync(token);
    }
}
