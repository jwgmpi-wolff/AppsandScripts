using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Options;

namespace StockStreamPortfolio.Api.Services;

public sealed class ProviderConfigurationValidatorHostedService : IHostedService
{
    private readonly IHostEnvironment _environment;
    private readonly IOptions<MarketDataProviderOptions> _options;
    private readonly ILogger<ProviderConfigurationValidatorHostedService> _logger;

    public ProviderConfigurationValidatorHostedService(
        IHostEnvironment environment,
        IOptions<MarketDataProviderOptions> options,
        ILogger<ProviderConfigurationValidatorHostedService> logger)
    {
        _environment = environment;
        _options = options;
        _logger = logger;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        var options = _options.Value;
        var hasBaseUrl = !string.IsNullOrWhiteSpace(options.BaseUrl);
        var hasSecretName = !string.IsNullOrWhiteSpace(options.ApiKeySecretName);
        var isPlaceholderProvider = string.Equals(options.ProviderName, "PlaceholderProvider", StringComparison.OrdinalIgnoreCase)
            || (options.BaseUrl?.Contains("example.invalid", StringComparison.OrdinalIgnoreCase) ?? false);

        if (!hasBaseUrl || !hasSecretName)
        {
            var message = "Market data provider is not fully configured. Set MarketDataProvider:BaseUrl and MarketDataProvider:ApiKeySecretName.";
            if (_environment.IsProduction())
            {
                throw new InvalidOperationException(message);
            }

            _logger.LogWarning("{Message}", message);
        }

        if (isPlaceholderProvider)
        {
            var message = "Market data provider is using placeholder values. Configure a real ProviderName/BaseUrl/API key to receive live and after-hours quotes.";
            _logger.LogWarning("{Message}", message);
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
