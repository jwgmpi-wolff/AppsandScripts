namespace StockStreamPortfolio.Api.Models;

public static class ColumnNames
{
    public const string Symbol = "Symbol";
    public const string Last = "Last";
    public const string Bid = "Bid";
    public const string Chg = "Chg";
    public const string Ask = "Ask";
    public const string TdyGL = "Tdy G/L";
    public const string Quantity = "Quantity";
    public const string Volume = "Volume";
    public const string DayRange = "Day Range";
    public const string FiftyTwoWkRange = "52 Wk Range";
    public const string PurchasePrice = "Purchase Price";
    public const string Value = "Value";
    public const string PercentTdyGL = "% Tdy G/L";
    public const string GL = "G/L";
    public const string PercentGL = "% G/L";
    public const string Account = "Account";
    public const string CloseValue = "Close Value";
    public const string EarningsDate = "Earnings Date";
    public const string DivDate = "Div Date";
    public const string PrevClose = "Prev Close";

    public static readonly string[] All =
    [
        Symbol,
        Last,
        Bid,
        Chg,
        Ask,
        TdyGL,
        Quantity,
        Volume,
        DayRange,
        FiftyTwoWkRange,
        PurchasePrice,
        Value,
        PercentTdyGL,
        GL,
        PercentGL,
        Account,
        CloseValue,
        EarningsDate,
        DivDate,
        PrevClose
    ];
}
