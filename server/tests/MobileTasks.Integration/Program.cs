using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Npgsql;

// Developer-only fixture: isolated PostgreSQL on 15439. NEVER deploy this
// runner to the server. Only this known fixture is reset between test runs.
if (args.Length != 3) throw new ArgumentException("Arguments: dotnet path, published API DLL, output directory");
var dotnet = Path.GetFullPath(args[0]);
var apiDll = Path.GetFullPath(args[1]);
var output = Path.GetFullPath(args[2]);
Directory.CreateDirectory(output);
const string fixturePassword = "test;quote\"slash\\";
var fixtureConnection = new NpgsqlConnectionStringBuilder
{
    Host = "127.0.0.1", Port = 15439, Username = "mobile_tasks_api",
    Password = fixturePassword, Database = "postgres", Timeout = 5
};
await using (var admin = NpgsqlDataSource.Create(fixtureConnection.ConnectionString))
{
    await using var exists = admin.CreateCommand("SELECT 1 FROM pg_database WHERE datname='mobile_tasks'");
    if (await exists.ExecuteScalarAsync() is null)
    {
        await using var create = admin.CreateCommand("CREATE DATABASE mobile_tasks");
        await create.ExecuteNonQueryAsync();
    }
}
fixtureConnection.Database = "mobile_tasks";
await using var database = NpgsqlDataSource.Create(fixtureConnection.ConnectionString);
async Task<object?> Scalar(string sql, params object[] values)
{
    await using var command = database.CreateCommand(sql);
    foreach (var value in values) command.Parameters.AddWithValue(value);
    return await command.ExecuteScalarAsync();
}
async Task Execute(string sql)
{
    await using var command = database.CreateCommand(sql);
    await command.ExecuteNonQueryAsync();
}
await Execute("DROP SCHEMA IF EXISTS mobile_tasks CASCADE");
var config = Path.Combine(output, "fixture-config.json");
await File.WriteAllTextAsync(config, JsonSerializer.Serialize(new { Database = new {
    Host = "127.0.0.1", Port = 15439, Name = "mobile_tasks", Username = "mobile_tasks_api", Password = fixturePassword
}, Push = new { OneCSecret = "fixture-only-webhook-secret-123456789" }}));
Process Start(bool migrate, string configPath)
{
    var info = new ProcessStartInfo(dotnet) { UseShellExecute = false, CreateNoWindow = true,
        RedirectStandardOutput = true, RedirectStandardError = true };
    info.ArgumentList.Add(apiDll);
    if (migrate) info.ArgumentList.Add("--migrate");
    else { info.ArgumentList.Add("--urls"); info.ArgumentList.Add("http://127.0.0.1:18220"); }
    info.Environment["MOBILETASKS_CONFIG_FILE"] = configPath;
    info.Environment["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1";
    return Process.Start(info) ?? throw new InvalidOperationException("Could not start API");
}
async Task<int> Migrate(string configPath)
{
    using var process = Start(true, configPath);
    var stdout = process.StandardOutput.ReadToEndAsync();
    var stderr = process.StandardError.ReadToEndAsync();
    await process.WaitForExitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(60)).Token);
    var text = await stdout + await stderr;
    if (text.Contains(fixturePassword)) throw new Exception("Migration leaked database password");
    return process.ExitCode;
}
var passed = 0;
void Check(bool condition, string name)
{
    if (!condition) throw new Exception("FAILED: " + name);
    Console.WriteLine("PASS " + ++passed + ": " + name);
}
var api = Start(false, config);
var apiOut = api.StandardOutput.ReadToEndAsync();
var apiErr = api.StandardError.ReadToEndAsync();
var previousLogs = "";
using var http = new HttpClient(new HttpClientHandler { UseProxy = false })
{ BaseAddress = new Uri("http://127.0.0.1:18220"), Timeout = TimeSpan.FromSeconds(15) };
async Task<(int Code, JsonNode? Body)> Request(string method, string path, object? body = null, string? token = null)
{
    using var request = new HttpRequestMessage(new HttpMethod(method), path);
    if (body is not null) request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
    if (token is not null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
    using var response = await http.SendAsync(request);
    var raw = await response.Content.ReadAsStringAsync();
    if (response.StatusCode == HttpStatusCode.InternalServerError) throw new Exception("Unexpected HTTP 500 at " + path);
    return ((int)response.StatusCode, string.IsNullOrWhiteSpace(raw) ? null : JsonNode.Parse(raw));
}
try
{
    for (var i = 0; i < 30; i++)
    {
        if (api.HasExited) throw new Exception("API exited unexpectedly");
        try { if ((await Request("GET", "/health/live")).Code == 200) break; }
        catch (HttpRequestException) { }
        await Task.Delay(200);
    }
    Check((await Request("GET", "/health/schema")).Code == 503, "schema readiness before migration");
    Check((await Request("GET", "/api/tasks")).Code == 503, "API blocked until migration");
    await Execute("CREATE SCHEMA mobile_tasks; CREATE TABLE mobile_tasks.users(existing integer)");
    Check(await Migrate(config) != 0, "conflicting schema causes migration failure");
    Check(await Scalar("SELECT to_regclass('mobile_tasks.schema_migrations')::text") is DBNull,
        "failed migration rolls back history and partial changes");
    await Execute("DROP SCHEMA mobile_tasks CASCADE");
    var concurrentMigrations = await Task.WhenAll(Migrate(config), Migrate(config));
    Check(concurrentMigrations.All(code => code == 0), "concurrent migrations serialize safely");
    Check(await Migrate(config) == 0, "migration can be repeated");
    Check(Convert.ToInt64(await Scalar("SELECT count(*) FROM mobile_tasks.schema_migrations")) == 4, "four migration records");
    Check((await Request("GET", "/health/schema")).Body?["schemaVersion"]?.GetValue<int>() == 4, "schema readiness version 4");
    Check((await Request("GET", "/api/tasks")).Code == 401, "anonymous tasks denied");
    var password = "valid-test-password-2026";
    var bad = await Request("POST", "/api/auth/register", new { email = "bad", name = "X", password });
    Check(bad.Code == 400, "invalid email rejected");
    Check((await Request("POST", "/api/auth/register", new { email = "short@example.test", name = "X", password = "short" })).Code == 400, "short password rejected");
    var first = await Request("POST", "/api/auth/register", new { email = "  First@Example.Test ", name = "Первый", password });
    var second = await Request("POST", "/api/auth/register", new { email = "second@example.test", name = "Второй", password });
    var third = await Request("POST", "/api/auth/register", new { email = "third@example.test", name = "Третий", password });
    Check(first.Code == 201 && second.Code == 201 && third.Code == 201, "register three accounts");
    var tokenA = first.Body!["token"]!.GetValue<string>();
    var tokenB = second.Body!["token"]!.GetValue<string>();
    var tokenC = third.Body!["token"]!.GetValue<string>();
    var userA = Guid.Parse(first.Body["user"]!["id"]!.GetValue<string>());
    Check(first.Body["user"]!["email"]!.GetValue<string>() == "first@example.test", "email normalized");
    Check((await Request("POST", "/api/auth/register", new { email = "FIRST@example.test", name = "Duplicate", password })).Code == 409, "duplicate email rejected case-insensitively");
    var storedHash = (string)(await Scalar("SELECT password_hash FROM mobile_tasks.users WHERE id=$1", userA))!;
    Check(storedHash != password && storedHash.Length > 80, "password stored as Identity hash");
    var sessionHash = (string)(await Scalar("SELECT token_hash FROM mobile_tasks.sessions WHERE user_id=$1", userA))!;
    Check(sessionHash != tokenA && sessionHash.Length == 64, "only token hash stored");
    Check((await Request("GET", "/api/auth/me", token: tokenA)).Body?["name"]?.GetValue<string>() == "Первый", "authenticated profile");
    Check((await Request("POST", "/api/auth/login", new { email = "first@example.test", password = "incorrect-password" })).Code == 401, "wrong password rejected");
    Check((await Request("POST", "/api/auth/login", new { email = "unknown@example.test", password })).Code == 401, "unknown account rejected");
    var login = await Request("POST", "/api/auth/login", new { email = "FIRST@example.test", password });
    Check(login.Code == 200 && login.Body?["token"]?.GetValue<string>() != tokenA, "login creates separate session");
    var taskId = Guid.NewGuid();
    var taskRequest = new { id = taskId, title = "Проверить отчёт ' SQL --", description = "Описание задачи", authorId = "forged", assigneeId = "forged", status = "done" };
    var created = await Request("POST", "/api/tasks", taskRequest, tokenA);
    Check(created.Code == 201 && created.Body?["status"]?.GetValue<string>() == "new", "create task with server-owned state");
    Check(created.Body?["authorId"]?.GetValue<string>() == userA.ToString(), "author cannot be spoofed");
    Check((await Request("POST", "/api/tasks", taskRequest, tokenA)).Code == 200, "create retry idempotent");
    Check((await Request("POST", "/api/tasks", taskRequest, tokenB)).Code == 409, "another user cannot reuse task ID");
    Check((await Request("POST", "/api/tasks", new { title = "xx" }, tokenA)).Code == 400, "invalid title rejected");
    Check((await Request("GET", $"/api/tasks/{taskId}", token: tokenB)).Code == 200, "tasks visible to all registered accounts");
    Check((await Request("GET", $"/api/tasks/{Guid.NewGuid()}", token: tokenB)).Code == 404, "missing task 404");
    var claims = await Task.WhenAll(Request("POST", $"/api/tasks/{taskId}/claim", token: tokenB), Request("POST", $"/api/tasks/{taskId}/claim", token: tokenC));
    Check(claims.Count(r => r.Code == 200) == 1 && claims.Count(r => r.Code == 409) == 1, "exactly one concurrent claimant wins");
    var winner = claims[0].Code == 200 ? tokenB : tokenC;
    var loser = claims[0].Code == 200 ? tokenC : tokenB;
    Check((await Request("POST", $"/api/tasks/{taskId}/claim", token: winner)).Code == 200, "claim retry idempotent");
    var history = await Request("GET", $"/api/tasks/{taskId}/events", token: winner);
    Check(history.Body!["items"]!.AsArray().Count(node => node!["kind"]!.GetValue<string>() == "claimed") == 1, "single claim event");
    Check(history.Body["items"]!.AsArray().Single(node => node!["kind"]!.GetValue<string>() == "claimed")!["comment"]!.GetValue<string>().EndsWith("@example.test"), "claim records programmer email");
    Check((await Request("POST", $"/api/tasks/{taskId}/complete", token: loser)).Code == 409, "other programmer cannot complete");
    Check((await Request("POST", $"/api/tasks/{taskId}/complete", token: tokenA)).Code == 409, "author cannot complete another assignee's task");
    Check((await Request("GET", "/api/tasks?mine=true&status=active", token: winner)).Body!["items"]!.AsArray().Count == 1, "own active task filter");
    Check((await Request("GET", "/api/tasks?mine=true", token: loser)).Body!["items"]!.AsArray().Count == 0, "own filter excludes other assignee");
    var commentId = Guid.NewGuid();
    var comment = new { id = commentId, comment = "Проверено клиентом" };
    Check((await Request("POST", $"/api/tasks/{taskId}/comments", comment, tokenA)).Code == 200, "add comment");
    Check((await Request("POST", $"/api/tasks/{taskId}/comments", comment, tokenA)).Code == 200, "comment retry idempotent");
    Check((await Request("POST", $"/api/tasks/{taskId}/comments", comment, tokenB)).Code == 409, "comment ID ownership protected");
    Check((await Request("POST", $"/api/tasks/{taskId}/complete", token: winner)).Body?["status"]?.GetValue<string>() == "done", "assignee completes task");
    Check((await Request("POST", $"/api/tasks/{taskId}/complete", token: winner)).Code == 200, "complete retry idempotent");
    Check((await Request("GET", "/api/tasks?mine=true&status=done", token: winner)).Body!["items"]!.AsArray().Count == 1, "own completed filter");
    Check(Convert.ToInt64(await Scalar("SELECT count(*) FROM mobile_tasks.task_events WHERE task_id=$1", taskId)) == 4, "created/claimed/comment/completed events persisted exactly once");
    await Execute("DROP TABLE mobile_tasks.push_queue; DROP TABLE mobile_tasks.devices; DROP TABLE mobile_tasks.task_files; DROP TABLE mobile_tasks.task_links; DELETE FROM mobile_tasks.schema_migrations WHERE version>=2");
    Check((await Request("GET", "/health/schema")).Code == 503, "v1 schema requires update");
    Check(await Migrate(config) == 0 && (await Request("GET", $"/api/tasks/{taskId}", token: tokenA)).Code == 200, "v1 upgrade preserves tasks and accounts");
    var linkId = Guid.NewGuid();
    var link = new { id = linkId, name = "Контакт клиента", url = "https://wa.me/996555123456", kind = "whatsapp", authorId = "spoofed" };
    var linkBody = new { items = new[] { link } };
    Check((await Request("POST", $"/api/tasks/{taskId}/links", linkBody)).Code == 401, "links require authentication");
    Check((await Request("POST", $"/api/tasks/{taskId}/links", linkBody, tokenA)).Code == 200, "add contact link");
    Check((await Request("POST", $"/api/tasks/{taskId}/links", linkBody, tokenA)).Code == 200, "link retry idempotent");
    Check((await Request("POST", $"/api/tasks/{taskId}/links", linkBody, tokenB)).Code == 409, "link author protected");
    var links = (await Request("GET", $"/api/tasks/{taskId}/links", token: tokenC)).Body!["items"]!.AsArray();
    Check(links.Count == 1 && links[0]!["authorId"]!.GetValue<string>() == userA.ToString(), "all users see links with real author");
    Check((await Request("POST", $"/api/tasks/{taskId}/links", new { items = new[] { new { id=Guid.NewGuid(), name="Unsafe",url="javascript:alert(1)",kind="web" } } }, tokenA)).Code == 400, "unsafe URI rejected");
    Check((await Request("POST", $"/api/tasks/{taskId}/links", new { items = new[] { new { id=Guid.NewGuid(), name="Wrong host",url="https://wa.me.example.test/123",kind="whatsapp" } } }, tokenA)).Code == 400, "contact hostname suffix spoof rejected");
    Check((await Request("POST", $"/api/tasks/{Guid.NewGuid()}/links", linkBody, tokenA)).Code == 404, "links cannot target missing task");
    var rollbackId = Guid.NewGuid();
    Check((await Request("POST", $"/api/tasks/{taskId}/links", new { items = new[] {
        new { id=rollbackId,name="First valid",url="https://example.test/",kind="web" },
        new { id=linkId,name="Conflicting",url="https://example.test/",kind="web" }
    } }, tokenA)).Code == 409, "conflicting link batch rejected");
    Check(Convert.ToInt64(await Scalar("SELECT count(*) FROM mobile_tasks.task_links WHERE id=$1",rollbackId)) == 0, "batch rollback leaves no partial links");
    var fileId=Guid.NewGuid();
    async Task<int> UploadFile(string authToken, Guid fileKey, string filename, byte[] bytes) {
        using var req=new HttpRequestMessage(HttpMethod.Put,$"/api/tasks/{taskId}/files/{fileKey}?name="+Uri.EscapeDataString(filename));
        req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",authToken);req.Content=new ByteArrayContent(bytes);
        using var res=await http.SendAsync(req);return (int)res.StatusCode;
    }
    var payload=Encoding.UTF8.GetBytes("Binary test content \0 image");
    Check(await UploadFile(tokenA,fileId,"Фото.png",payload)==200,"file upload");
    Check(await UploadFile(tokenA,fileId,"Фото.png",payload)==200,"file upload retry idempotent");
    Check(await UploadFile(tokenB,fileId,"Фото.png",payload)==409,"file ownership protected");
    Check(await UploadFile(tokenA,Guid.NewGuid(),"../bad",payload)==400,"filename traversal rejected");
    Check(await UploadFile(tokenA,Guid.NewGuid(),"empty",[])==400,"empty file rejected");
    Check((await Request("GET",$"/api/tasks/{taskId}/files",token:tokenC)).Body!["items"]!.AsArray().Count==1,"files visible to registered users");
    using(var req=new HttpRequestMessage(HttpMethod.Get,$"/api/tasks/{taskId}/files/{fileId}")) {
        req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",tokenB);
        using var res=await http.SendAsync(req);
        Check(res.IsSuccessStatusCode && (await res.Content.ReadAsByteArrayAsync()).SequenceEqual(payload),"binary download roundtrip");
    }
    Check((await Request("GET",$"/api/tasks/{taskId}/files/{fileId}")).Code==401,"anonymous download rejected");
    var actionId=Guid.NewGuid();
    await Request("POST","/api/tasks",new{id=actionId,title="Действия задачи"},tokenA);
    var idB=second.Body!["user"]!["id"]!.GetValue<string>();
    var idC=third.Body!["user"]!["id"]!.GetValue<string>();
    Check((await Request("GET","/api/users",token:tokenA)).Body!["items"]!.AsArray().Count==3,"list users for transfer");
    await Request("POST",$"/api/tasks/{actionId}/claim",token:tokenB);
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="release"},tokenC)).Code==403,"other user cannot release");
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="release"},tokenB)).Body?["status"]?.GetValue<string>()=="new","assignee releases task");
    await Request("POST",$"/api/tasks/{actionId}/claim",token:tokenB);
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="transfer",target=idB},tokenB)).Code==400,"self transfer rejected");
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="transfer",target=idC},tokenB)).Body?["assigneeId"]?.GetValue<string>()==idC,"transfer changes assignee");
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="cancel"},tokenC)).Code==403,"only author can cancel");
    Check((await Request("POST",$"/api/tasks/{actionId}/action",new{action="cancel"},tokenA)).Body?["status"]?.GetValue<string>()=="canceled","author cancels task");
    Check((await Request("POST",$"/api/tasks/{actionId}/claim",token:tokenB)).Code==409,"canceled task cannot be claimed");
    Check(Convert.ToInt64(await Scalar("SELECT count(*) FROM mobile_tasks.push_queue WHERE kind='common'"))>=1,"new task push persists in outbox");
    Check((await Request("POST","/api/devices",new{id=Guid.NewGuid(),token="test-device-token"})).Code==401,"device requires auth");
    Check((await Request("POST","/api/devices",new{id=Guid.NewGuid(),token="test-device-token"},tokenA)).Code==200,"register push device");
    Check((await Request("POST","/api/integrations/1c/new-task",new{taskId=Guid.NewGuid(),source="https://example.test/base/odata/standard.odata/"})).Code==401,"webhook rejects missing key");
    http.DefaultRequestHeaders.Add("X-OneC-Key","fixture-only-webhook-secret-123456789");
    var webhook = new { taskId=Guid.NewGuid(),source="https://example.test/base/odata/standard.odata/" };
    Check((await Request("POST","/api/integrations/1c/new-task",webhook)).Code==200,"authenticated 1C webhook queues push");
    Check((await Request("POST","/api/integrations/1c/new-task",webhook)).Code==200,"1C webhook retry accepted");
    Check(Convert.ToInt64(await Scalar("SELECT count(*) FROM mobile_tasks.push_queue WHERE kind='odata'"))==1,"1C duplicate event removed");
    http.DefaultRequestHeaders.Remove("X-OneC-Key");
    api.Kill(entireProcessTree: true);
    await api.WaitForExitAsync();
    previousLogs = await apiOut + await apiErr;
    api.Dispose();
    api = Start(false, config);
    apiOut = api.StandardOutput.ReadToEndAsync();
    apiErr = api.StandardError.ReadToEndAsync();
    for (var i=0; i<30; i++)
    {
        try { if ((await Request("GET", "/health/live")).Code == 200) break; }
        catch (HttpRequestException) { }
        await Task.Delay(200);
    }
    Check((await Request("GET", $"/api/tasks/{taskId}", token: winner)).Body?["status"]?.GetValue<string>() == "done",
        "task state and session survive API restart");
    Check((await Request("GET", "/api/tasks?limit=101", token: tokenA)).Code == 400 && (await Request("GET", "/api/tasks?cursor=bad!", token: tokenA)).Code == 400, "invalid paging inputs rejected");
    Check((await Request("GET", "/api/tasks?status=forged", token: tokenA)).Code == 400, "invalid status rejected");
    for (var i = 0; i < 3; i++) await Request("POST", "/api/tasks", new { id = Guid.NewGuid(), title = "Пагинация " + i }, tokenA);
    var firstPage = await Request("GET", "/api/tasks?limit=2", token: tokenA);
    var next = firstPage.Body!["nextCursor"]!.GetValue<string>();
    var secondPage = await Request("GET", "/api/tasks?limit=2&cursor=" + Uri.EscapeDataString(next), token: tokenA);
    var allIds = firstPage.Body["items"]!.AsArray().Concat(secondPage.Body!["items"]!.AsArray()).Select(node => node!["id"]!.GetValue<string>()).ToArray();
    Check(allIds.Length == 4 && allIds.Distinct().Count() == 4, "keyset paging without duplicates or omissions");
    await Scalar("UPDATE mobile_tasks.sessions SET expires_at=now()-interval '1 second' WHERE token_hash=$1", Hash(login.Body!["token"]!.GetValue<string>()));
    Check((await Request("GET", "/api/auth/me", token: login.Body["token"]!.GetValue<string>())).Code == 401, "expired token rejected");
    Check((await Request("POST", "/api/auth/logout", token: tokenA)).Code == 204 && (await Request("GET", "/api/auth/me", token: tokenA)).Code == 401, "logout revokes session");
    for (var i = 0; i < 5; i++) await Request("POST", "/api/auth/login", new { email = "first@example.test", password = "incorrect-password" });
    Check((await Request("POST", "/api/auth/login", new { email = "first@example.test", password })).Code == 401, "account temporarily locked after five failures");
    await Execute("UPDATE mobile_tasks.users SET locked_until=now()-interval '1 minute' WHERE email='first@example.test'");
    Check((await Request("POST", "/api/auth/login", new { email = "first@example.test", password })).Code == 200, "login works after lock expiry");
    await Execute("INSERT INTO mobile_tasks.schema_migrations(version) VALUES (99)");
    Check(await Migrate(config) != 0 && (await Request("GET", "/api/tasks", token: winner)).Code == 503, "future schema refused");
    await Execute("DELETE FROM mobile_tasks.schema_migrations WHERE version=99");
    var wrongConfig = Path.Combine(output, "wrong-database.json");
    await File.WriteAllTextAsync(wrongConfig, (await File.ReadAllTextAsync(config)).Replace("\"Name\":\"mobile_tasks\"", "\"Name\":\"postgres\""));
    Check(await Migrate(wrongConfig) != 0, "migration refuses non-application database");
    for (var i = 0; i < 30; i++)
    {
        var limited = await Request("POST", "/api/auth/login", new { email = "unknown@example.test", password });
        if (limited.Code == 429) { Check(true, "authentication rate limit"); break; }
        if (i == 29) throw new Exception("Rate limit did not activate");
    }
    var hashes = await Scalar("SELECT count(*) FROM mobile_tasks.sessions WHERE token_hash=$1", Hash(tokenB));
    Check(Convert.ToInt64(hashes) == 1, "other account session remains intact");
}
finally
{
    if (!api.HasExited) api.Kill(entireProcessTree: true);
    await api.WaitForExitAsync();
    var logs = previousLogs + await apiOut + await apiErr;
    await File.WriteAllTextAsync(Path.Combine(output, "api.log"), logs);
    if (logs.Contains(fixturePassword) || logs.Contains("valid-test-password-2026")) throw new Exception("Secret leaked into logs");
    api.Dispose();
}
Console.WriteLine("All " + passed + " integration assertions passed.");
static string Hash(string token) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(token)));
