using System.Security.Claims;
using Azure.Extensions.AspNetCore.Configuration.Secrets;
using Azure.Identity;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Identity.Web;
using StockStreamPortfolio.Api.Options;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddApplicationInsightsTelemetry();
builder.Services.AddHttpContextAccessor();

builder.Configuration.AddEnvironmentVariables(prefix: "STOCKSTREAM_");

var keyVaultUri = builder.Configuration["KeyVault:Uri"];
if (!string.IsNullOrWhiteSpace(keyVaultUri))
{
    builder.Configuration.AddAzureKeyVault(new Uri(keyVaultUri), new DefaultAzureCredential(), new KeyVaultSecretManager());
}

builder.Services.Configure<MarketDataProviderOptions>(builder.Configuration.GetSection(MarketDataProviderOptions.SectionName));
builder.Services.Configure<RefreshPolicyOptions>(builder.Configuration.GetSection(RefreshPolicyOptions.SectionName));

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddMicrosoftIdentityWebApi(builder.Configuration.GetSection("AzureAd"));

builder.Services.Configure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, options =>
{
    var originalOnMessageReceived = options.Events?.OnMessageReceived;
    options.Events ??= new JwtBearerEvents();
    options.Events.OnMessageReceived = async context =>
    {
        if (originalOnMessageReceived is not null)
        {
            await originalOnMessageReceived(context);
        }

        if (!string.IsNullOrWhiteSpace(context.Token))
        {
            return;
        }

        var accessToken = context.Request.Query["access_token"];
        var path = context.HttpContext.Request.Path;
        if (!string.IsNullOrWhiteSpace(accessToken) && path.StartsWithSegments("/hubs/quotes"))
        {
            context.Token = accessToken;
        }
    };
});

builder.Services.AddAuthorization(options =>
{
    options.AddPolicy(AuthorizationPolicies.Admin, policy => policy.RequireRole("Admin"));
    options.AddPolicy(AuthorizationPolicies.User, policy => policy.RequireRole("User", "Admin"));
});

builder.Services.AddCors(options =>
{
    options.AddPolicy("ApprovedOrigins", policy =>
    {
        var origins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>() ?? Array.Empty<string>();
        if (origins.Length > 0)
        {
            policy.WithOrigins(origins)
                .AllowAnyHeader()
                .AllowAnyMethod()
                .AllowCredentials();
        }
    });
});

builder.Services.AddSignalR();
builder.Services.AddSingleton<IUserIdProvider, EntraUserIdProvider>();
builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

builder.Services.AddSingleton<IWatchlistStore, InMemoryWatchlistStore>();
builder.Services.AddSingleton<IColumnLayoutStore, InMemoryColumnLayoutStore>();
builder.Services.AddSingleton<IRotatingViewStore, InMemoryRotatingViewStore>();
builder.Services.AddSingleton<ISettingsStore, InMemorySettingsStore>();
builder.Services.AddSingleton<IAdminStore, InMemoryAdminStore>();
builder.Services.AddSingleton<ICsvPortfolioParser, CsvPortfolioParser>();
builder.Services.AddSingleton<ISymbolValidator, SymbolValidator>();
builder.Services.AddSingleton<INoHallucinationGuard, NoHallucinationGuard>();
builder.Services.AddHostedService<QuoteBroadcastHostedService>();

builder.Services.AddHttpClient<IMarketDataProvider, ConfigurableMarketDataProvider>();
builder.Services.AddHostedService<ProviderConfigurationValidatorHostedService>();

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseHttpsRedirection();
app.UseCors("ApprovedOrigins");
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/health", () => Results.Ok(new { status = "ok", utc = DateTime.UtcNow }))
    .AllowAnonymous();

app.MapGet("/me", [Authorize(Policy = AuthorizationPolicies.User)] (ClaimsPrincipal user) =>
{
    var name = user.Identity?.Name ?? "unknown";
    var objectId = user.GetLocalObjectId() ?? "unknown";
    var roles = user.Claims.Where(c => c.Type == ClaimTypes.Role || c.Type == "roles").Select(c => c.Value).ToArray();

    return Results.Ok(new
    {
        displayName = name,
        objectId,
        roles
    });
});

app.MapHub<QuoteHub>("/hubs/quotes");
app.MapControllers();

app.Run();

namespace StockStreamPortfolio.Api
{
    public partial class Program;
}
