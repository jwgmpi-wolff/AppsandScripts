namespace StockStreamPortfolio.Api.Options;

public sealed class RefreshPolicyOptions
{
    public const string SectionName = "RefreshPolicy";

    public int AdminMinSeconds { get; set; } = 5;
    public int AdminMaxSeconds { get; set; } = 120;
    public int DefaultSeconds { get; set; } = 15;
}
