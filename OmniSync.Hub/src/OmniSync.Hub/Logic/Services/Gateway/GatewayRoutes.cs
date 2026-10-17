using System;
using System.IO;
using System.Collections.Generic;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace OmniSync.Hub.Logic.Services.Gateway;

public static class GatewayRoutes
{
    // The Gateway is accessible from other devices via the machine's LAN IP.
    // Bind these endpoints to the web-server port, never the Hub's 5000/5055 ports.
    internal static bool IsGatewayPort(HttpContext context) =>
        context.Connection.LocalPort == 3333;

    private static IResult Denied() => Results.NotFound();

    // Must run before static files, which would otherwise serve the Hub dashboard for "/".
    // The dashboard stays reachable at /index.html.
    public static void UseRootRedirect(WebApplication app)
    {
        app.Use(async (ctx, next) =>
        {
            // Opt-in Gateway + OmniSweep browser delegation only. C# still owns
            // the public :3333 socket and all unrelated C# API/static routes.
            if (IsGatewayPort(ctx) && GatewayDelegation.Enabled &&
                GatewayDelegation.Matches(ctx.Request.Path))
            {
                await GatewayDelegation.ProxyAsync(ctx);
                return;
            }
            if (IsGatewayPort(ctx) && ctx.Request.Path == "/")
            {
                ctx.Response.Redirect("/gateway/");
                return;
            }
            await next();
        });
    }

    public static void Map(WebApplication app)
    {
        app.MapGet("/gateway", (HttpContext ctx) =>
            IsGatewayPort(ctx) ? Results.Redirect("/gateway/index.html") : Denied());

        // Read-only Tailscale node, Serve/Funnel URLs and devices for the Gateway overview.
        app.MapGet("/gateway/api/tailscale", async (HttpContext ctx) =>
            IsGatewayPort(ctx) ? Results.Ok(await TailscaleStatusService.SnapshotAsync()) : Denied());

        app.MapGet("/gateway/api/tools", async (HttpContext ctx, GatewayToolManager manager) =>
        {
            if (!IsGatewayPort(ctx)) return Denied();
            try { return Results.Ok(await manager.StatusAsync()); }
            catch (InvalidDataException ex) { return Results.Problem(ex.Message, statusCode: 500); }
        });

        app.MapPost("/gateway/api/launch/{id}", async (HttpContext ctx, string id, GatewayToolManager manager) =>
        {
            if (!IsGatewayPort(ctx)) return Denied();
            try
            {
                var result = await manager.LaunchAsync(id);
                return result.Ok ? Results.Ok(new { url = result.Url }) :
                    Results.Json(new { error = result.Error }, statusCode: 503);
            }
            catch (InvalidDataException ex) { return Results.Problem(ex.Message, statusCode: 500); }
        });

        app.MapGet("/gateway/api/log/{id}", (HttpContext ctx, string id, GatewayToolManager manager) =>
        {
            if (!IsGatewayPort(ctx)) return Denied();
            try { return Results.Text(manager.ReadLog(id), "text/plain"); }
            catch (KeyNotFoundException) { return Results.NotFound(); }
            catch (InvalidDataException ex) { return Results.Problem(ex.Message, statusCode: 500); }
        });

        // Single path segment only; controllers/static files retain precedence.
        app.MapGet("/{gatewayToolId}", (HttpContext ctx, string gatewayToolId, GatewayToolManager manager) =>
        {
            if (!IsGatewayPort(ctx)) return Denied();
            try
            {
                return manager.Find(gatewayToolId) is not null
                    ? Results.Redirect("/gateway/launch.html?tool=" + Uri.EscapeDataString(gatewayToolId))
                    : Results.NotFound();
            }
            catch (InvalidDataException ex) { return Results.Problem(ex.Message, statusCode: 500); }
        });
    }
}
