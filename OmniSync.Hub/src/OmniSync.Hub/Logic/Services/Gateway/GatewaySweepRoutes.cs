using System;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace OmniSync.Hub.Logic.Services.Gateway;

public static class GatewaySweepRoutes
{
    private static IResult Mutation(SweepMutationResult result) =>
        Results.Json(new { ok = result.Ok, error = result.Error, revision = result.Revision },
            statusCode: result.Status);

    public static void Map(WebApplication app)
    {
        var group = app.MapGroup("/gateway/api/sweep");
        // All Gateway APIs are directly available on web port 3333, including LAN.
        // Keep a request size bound so malformed clients cannot allocate arbitrarily.
        group.AddEndpointFilter(async (invocation, next) =>
        {
            var ctx = invocation.HttpContext;
            if (!GatewayRoutes.IsGatewayPort(ctx)) return Results.NotFound();
            if (ctx.Request.ContentLength > 128 * 1024)
                return Results.StatusCode(413);
            return await next(invocation);
        });
        group.MapGet("", async (GatewaySweepService sweep) => Results.Ok(await sweep.SnapshotAsync()));
        group.MapGet("/search", async (GatewaySweepService sweep, string? query) =>
            Results.Ok(await sweep.SearchAsync(query ?? "")));
        group.MapPost("/setups", async (GatewaySweepService sweep, SweepCreate request) =>
            Mutation(await sweep.CreateAsync(request)));
        group.MapPut("/setups/{id:guid}", async (GatewaySweepService sweep, Guid id, SweepEdit request) =>
            Mutation(await sweep.UpdateAsync(id, request)));
        group.MapPost("/setups/{id:guid}/delete", async (GatewaySweepService sweep, Guid id, SweepRevision request) =>
            Mutation(await sweep.DeleteAsync(id, request)));
        group.MapPut("/order", async (GatewaySweepService sweep, SweepReorder request) =>
            Mutation(await sweep.ReorderAsync(request)));
        group.MapPost("/setups/{id:guid}/run", async (GatewaySweepService sweep, Guid id) =>
            await sweep.LaunchAsync(id) ? Results.Accepted() : Results.NotFound());
        group.MapPost("/macros/{id:guid}/run", async (GatewaySweepService sweep, Guid id) =>
            await sweep.RunMacroAsync(id) ? Results.Accepted() : Results.NotFound());
        group.MapPost("/snapshot", async (GatewaySweepService sweep, SweepSnapshot request) =>
            await sweep.RequestSnapshotAsync(request.Id, request.Replace)
                ? Results.Accepted() : Results.NotFound());
        group.MapPost("/search/open", async (GatewaySweepService sweep, SweepOpen request) =>
            await sweep.OpenSearchResultAsync(request.Query ?? "", request.Path ?? "")
                ? Results.Accepted() : Results.BadRequest(new { error = "Search result no longer exists." }));
    }
}
