using FluentAssertions;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Unit;

public sealed class SymbolValidatorTests
{
    private readonly SymbolValidator _validator = new();

    [Theory]
    [InlineData("MSFT")]
    [InlineData("BRK.B")]
    [InlineData("09261F655")]
    public void IsValidFormat_AllowsCommonFormats(string symbol)
    {
        _validator.IsValidFormat(symbol).Should().BeTrue();
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData("INVALID*SYMBOL")]
    public void IsValidFormat_RejectsInvalidFormats(string symbol)
    {
        _validator.IsValidFormat(symbol).Should().BeFalse();
    }
}
