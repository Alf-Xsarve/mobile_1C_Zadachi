using MobileTasks.Api.Api;
using MobileTasks.Api.Data;
using Npgsql;

namespace MobileTasks.Api.Tasks;

public sealed class TaskStore(Database database)
{
    private const string SelectTask = """
        SELECT t.id,t.title,t.description,t.status,t.author_id,a.name,t.assignee_id,s.name,
               t.created_at,t.updated_at,t.completed_at
        FROM mobile_tasks.tasks t
        JOIN mobile_tasks.users a ON a.id=t.author_id
        LEFT JOIN mobile_tasks.users s ON s.id=t.assignee_id
        """;

    private static TaskView ReadTask(NpgsqlDataReader reader) => new(
        reader.GetGuid(0), reader.GetString(1), reader.GetString(2), reader.GetString(3),
        reader.GetGuid(4), reader.GetString(5), reader.IsDBNull(6) ? null : reader.GetGuid(6),
        reader.IsDBNull(7) ? null : reader.GetString(7), reader.GetDateTime(8), reader.GetDateTime(9),
        reader.IsDBNull(10) ? null : reader.GetDateTime(10));

    public async Task<TaskView> GetAsync(Guid id, CancellationToken token)
    {
        await using var command = database.Source.CreateCommand(SelectTask + " WHERE t.id=$1");
        command.Parameters.AddWithValue(id);
        await using var reader = await command.ExecuteReaderAsync(token);
        if (!await reader.ReadAsync(token)) throw new ApiError(404, "task_not_found", "Задача не найдена.");
        return ReadTask(reader);
    }

    public async Task<TaskPage> ListAsync(Guid userId, string? status, bool mine, int limit, string? cursor, CancellationToken token)
    {
        if (status is not (null or "" or "new" or "active" or "done")) throw new ApiError(400, "invalid_status", "Неизвестный статус.");
        if (limit is < 1 or > 100) throw new ApiError(400, "invalid_limit", "Размер страницы: от 1 до 100.");
        var page = PageCursor.Parse(cursor);
        await using var command = database.Source.CreateCommand(SelectTask + """
             WHERE ($1='' OR t.status=$1) AND ($2='00000000-0000-0000-0000-000000000000'::uuid OR t.assignee_id=$2)
             AND (t.created_at,t.id)<($3,$4) ORDER BY t.created_at DESC,t.id DESC LIMIT $5
            """);
        command.Parameters.AddWithValue(status ?? "");
        command.Parameters.AddWithValue(mine ? userId : Guid.Empty);
        command.Parameters.AddWithValue(page.Time);
        command.Parameters.AddWithValue(page.Id);
        command.Parameters.AddWithValue(limit + 1);
        var tasks = new List<TaskView>();
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token)) tasks.Add(ReadTask(reader));
        string? next = tasks.Count > limit ? PageCursor.Encode(tasks[limit-1].CreatedAt, tasks[limit-1].Id) : null;
        return new(tasks.Take(limit).ToArray(), next);
    }

    public async Task<(TaskView Task, bool Created)> CreateAsync(Guid actor, CreateTaskRequest request, CancellationToken token)
    {
        var title = request.Title?.Trim() ?? "";
        var description = request.Description ?? "";
        if (title.Length is < 3 or > 160) throw new ApiError(400, "invalid_title", "Название: от 3 до 160 символов.");
        if (description.Length > 10000) throw new ApiError(400, "invalid_description", "Описание: до 10000 символов.");
        var id = request.Id ?? Guid.NewGuid();
        if (id == Guid.Empty) throw new ApiError(400, "invalid_id", "Укажите непустой UUID.");
        await using var connection = await database.Source.OpenConnectionAsync(token);
        await using var transaction = await connection.BeginTransactionAsync(token);
        await using var insert = new NpgsqlCommand("""
            INSERT INTO mobile_tasks.tasks(id,title,description,author_id) VALUES ($1,$2,$3,$4)
            ON CONFLICT (id) DO NOTHING
            """, connection, transaction);
        insert.Parameters.AddWithValue(id);
        insert.Parameters.AddWithValue(title);
        insert.Parameters.AddWithValue(description);
        insert.Parameters.AddWithValue(actor);
        var created = await insert.ExecuteNonQueryAsync(token) == 1;
        if (created) await EventAsync(connection, transaction, id, actor, "created", "", token);
        else
        {
            await using var existing = new NpgsqlCommand("SELECT author_id,title,description FROM mobile_tasks.tasks WHERE id=$1", connection, transaction);
            existing.Parameters.AddWithValue(id);
            await using var reader = await existing.ExecuteReaderAsync(token);
            if (!await reader.ReadAsync(token) || reader.GetGuid(0) != actor || reader.GetString(1) != title || reader.GetString(2) != description)
                throw new ApiError(409, "task_id_conflict", "Этот UUID уже использован для другой задачи.");
        }
        await transaction.CommitAsync(token);
        return (await GetAsync(id, token), created);
    }

    public async Task<TaskView> TransitionAsync(Guid id, Guid actor, string actorEmail, bool complete, CancellationToken token)
    {
        await using var connection = await database.Source.OpenConnectionAsync(token);
        await using var transaction = await connection.BeginTransactionAsync(token);
        // Both eligibility and assignment are checked by one PostgreSQL UPDATE.
        // Concurrent claims cannot both succeed. Repeated requests by the winner
        // return the same state without inserting a second history event.
        var sql = complete
            ? "UPDATE mobile_tasks.tasks SET status='done',completed_at=now(),updated_at=now() WHERE id=$1 AND status='active' AND assignee_id=$2"
            : "UPDATE mobile_tasks.tasks SET status='active',assignee_id=$2,updated_at=now() WHERE id=$1 AND status='new' AND assignee_id IS NULL";
        await using var update = new NpgsqlCommand(sql, connection, transaction);
        update.Parameters.AddWithValue(id);
        update.Parameters.AddWithValue(actor);
        var changed = await update.ExecuteNonQueryAsync(token) == 1;
        if (changed) await EventAsync(connection, transaction, id, actor, complete ? "completed" : "claimed", actorEmail, token);
        else
        {
            await using var existing = new NpgsqlCommand("SELECT status,assignee_id FROM mobile_tasks.tasks WHERE id=$1", connection, transaction);
            existing.Parameters.AddWithValue(id);
            await using var reader = await existing.ExecuteReaderAsync(token);
            if (!await reader.ReadAsync(token)) throw new ApiError(404, "task_not_found", "Задача не найдена.");
            var own = !reader.IsDBNull(1) && reader.GetGuid(1) == actor;
            var status = reader.GetString(0);
            if (!(own && (complete ? status == "done" : status is "active" or "done")))
                throw new ApiError(409, "task_state_conflict", complete ? "Завершить задачу может только её исполнитель." : "Задачу уже взял другой пользователь.");
        }
        await transaction.CommitAsync(token);
        return await GetAsync(id, token);
    }

    private static async Task EventAsync(NpgsqlConnection connection, NpgsqlTransaction transaction,
        Guid taskId, Guid actor, string kind, string comment, CancellationToken token)
    {
        await using var command = new NpgsqlCommand("INSERT INTO mobile_tasks.task_events(id,task_id,actor_id,kind,comment) VALUES ($1,$2,$3,$4,$5)", connection, transaction);
        command.Parameters.AddWithValue(Guid.NewGuid());
        command.Parameters.AddWithValue(taskId);
        command.Parameters.AddWithValue(actor);
        command.Parameters.AddWithValue(kind);
        command.Parameters.AddWithValue(comment);
        await command.ExecuteNonQueryAsync(token);
    }

    public async Task<EventPage> EventsAsync(Guid id, int limit, string? cursor, CancellationToken token)
    {
        if (limit is < 1 or > 100) throw new ApiError(400, "invalid_limit", "Размер страницы: от 1 до 100.");
        await GetAsync(id, token);
        var page = PageCursor.Parse(cursor);
        await using var command = database.Source.CreateCommand("""
            SELECT e.id,e.task_id,e.actor_id,u.name,e.kind,e.comment,e.created_at
            FROM mobile_tasks.task_events e JOIN mobile_tasks.users u ON u.id=e.actor_id
            WHERE e.task_id=$1 AND (e.created_at,e.id)<($2,$3)
            ORDER BY e.created_at DESC,e.id DESC LIMIT $4
            """);
        command.Parameters.AddWithValue(id);
        command.Parameters.AddWithValue(page.Time);
        command.Parameters.AddWithValue(page.Id);
        command.Parameters.AddWithValue(limit + 1);
        var items = new List<EventView>();
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token)) items.Add(new(reader.GetGuid(0), reader.GetGuid(1), reader.GetGuid(2),
            reader.GetString(3), reader.GetString(4), reader.GetString(5), reader.GetDateTime(6)));
        var next = items.Count > limit ? PageCursor.Encode(items[limit-1].CreatedAt, items[limit-1].Id) : null;
        return new(items.Take(limit).ToArray(), next);
    }

    public async Task<Guid> CommentAsync(Guid taskId, Guid actor, CommentRequest request, CancellationToken token)
    {
        var text = request.Comment?.Trim() ?? "";
        if (text.Length is < 1 or > 4000) throw new ApiError(400, "invalid_comment", "Комментарий: от 1 до 4000 символов.");
        var id = request.Id ?? Guid.NewGuid();
        if (id == Guid.Empty) throw new ApiError(400, "invalid_id", "Укажите непустой UUID.");
        await GetAsync(taskId, token);
        await using var command = database.Source.CreateCommand("""
            INSERT INTO mobile_tasks.task_events(id,task_id,actor_id,kind,comment) VALUES ($1,$2,$3,'comment',$4)
            ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
            WHERE task_events.task_id=EXCLUDED.task_id AND task_events.actor_id=EXCLUDED.actor_id
            AND task_events.kind='comment' AND task_events.comment=EXCLUDED.comment
            RETURNING id
            """);
        command.Parameters.AddWithValue(id);
        command.Parameters.AddWithValue(taskId);
        command.Parameters.AddWithValue(actor);
        command.Parameters.AddWithValue(text);
        if (await command.ExecuteScalarAsync(token) is not Guid)
            throw new ApiError(409, "comment_id_conflict", "UUID комментария уже использован.");
        return id;
    }
}
