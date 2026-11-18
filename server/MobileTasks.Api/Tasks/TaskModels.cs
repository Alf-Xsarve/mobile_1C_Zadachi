using System.Globalization;
using Microsoft.AspNetCore.WebUtilities;
using System.Text;
using MobileTasks.Api.Api;

namespace MobileTasks.Api.Tasks;

public sealed record TaskView(Guid Id, string Title, string Description, string Status,
    Guid AuthorId, string AuthorName, Guid? AssigneeId, string? AssigneeName,
    DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt, DateTimeOffset? CompletedAt);
public sealed record EventView(Guid Id, Guid TaskId, Guid ActorId, string ActorName,
    string Kind, string Comment, DateTimeOffset CreatedAt);
public sealed record TaskPage(IReadOnlyList<TaskView> Items, string? NextCursor);
public sealed record EventPage(IReadOnlyList<EventView> Items, string? NextCursor);
public sealed record CreateTaskRequest(Guid? Id, string? Title, string? Description);
public sealed record CommentRequest(Guid? Id, string? Comment);

public sealed record PageCursor(DateTime Time, Guid Id)
{
    public static PageCursor Parse(string? raw)
    {
        if (string.IsNullOrEmpty(raw)) return new(DateTime.SpecifyKind(DateTime.MaxValue, DateTimeKind.Utc), Guid.Parse("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        try
        {
            if (raw.Length > 200) throw new FormatException();
            var parts = Encoding.UTF8.GetString(WebEncoders.Base64UrlDecode(raw)).Split('|');
            if (parts.Length != 2 || !DateTimeOffset.TryParseExact(parts[0], "O", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var time) || !Guid.TryParse(parts[1], out var id)) throw new FormatException();
            return new(time.UtcDateTime, id);
        }
        catch (Exception error) when (error is FormatException or ArgumentException)
        { throw new ApiError(400, "invalid_cursor", "Некорректный указатель страницы."); }
    }

    public static string Encode(DateTimeOffset time, Guid id) =>
        WebEncoders.Base64UrlEncode(Encoding.UTF8.GetBytes(time.ToString("O", CultureInfo.InvariantCulture) + "|" + id));
}
