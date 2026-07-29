using FluentAssertions;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Unit;

public sealed class CsvPortfolioParserTests
{
    [Fact]
    public void ValidateAndParse_ReturnsErrors_WhenRequiredColumnMissing()
    {
        var parser = new CsvPortfolioParser();
        var csv = "Symbol,Last\nMSFT,100";

        var result = parser.ValidateAndParse(csv);

        result.IsValid.Should().BeFalse();
        result.Errors.Should().NotBeEmpty();
    }

    [Fact]
    public void ValidateAndParse_ParsesExpectedRows()
    {
        var parser = new CsvPortfolioParser();
        var csv = "Symbol,Last,Bid,Chg,Ask,Tdy G/L,Quantity,Volume,Day Range,52 Wk Range,Purchase Price,Value,% Tdy G/L,G/L,% G/L,Account,Close Value,Earnings Date,Div Date,Prev Close\nMSFT,100,99,-1,101,,5,1000,99-101,80-120,90,500,,50,10,IRA,480,2026-10-01,2026-09-01,98";

        var result = parser.ValidateAndParse(csv);

        result.IsValid.Should().BeTrue();
        result.ParsedRows.Should().HaveCount(1);
        result.ParsedRows.First().Symbol.Should().Be("MSFT");
    }
}
