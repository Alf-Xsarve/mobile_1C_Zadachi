using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace MobileTasks.Api.Auth;

public sealed class SessionAuthentication(
    IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger,
    UrlEncoder encoder, Accounts accounts) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public static string? Token(HttpContext context)
    {
        var header = context.Request.Headers.Authorization.ToString();
        return header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ? header[7..].Trim() : null;
    }
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var token = Token(Context);
        if (token is null) return AuthenticateResult.NoResult();
        var user = await accounts.ResolveAsync(token, Context.RequestAborted);
        if (user is null) return AuthenticateResult.Fail("Invalid or expired session.");
        var identity = new ClaimsIdentity([
            new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
            new Claim(ClaimTypes.Email, user.Email), new Claim(ClaimTypes.Name, user.Name)
        ], Scheme.Name);
        return AuthenticateResult.Success(new(new ClaimsPrincipal(identity), Scheme.Name));
    }

    protected override Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.StatusCode = 401;
        Response.Headers.WWWAuthenticate = "Bearer";
        return Response.WriteAsJsonAsync(new { code = "authentication_required", message = "Войдите в приложение." });
    }
}
