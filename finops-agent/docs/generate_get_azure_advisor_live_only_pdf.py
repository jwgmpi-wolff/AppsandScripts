from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import ListFlowable, ListItem, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "COPILOT_STUDIO_GET_AZURE_ADVISOR_LIVE_ONLY.pdf"


def bullets(items: list[str], style: ParagraphStyle) -> ListFlowable:
    return ListFlowable(
        [ListItem(Paragraph(item, style), leftIndent=12) for item in items],
        bulletType="bullet",
        leftIndent=20,
        bulletFontSize=8,
        spaceAfter=8,
    )


def table(rows: list[list[str]], widths: list[float], body: ParagraphStyle, header: ParagraphStyle) -> Table:
    wrapped = [
        [Paragraph(cell, header if row_index == 0 else body) for cell in row]
        for row_index, row in enumerate(rows)
    ]
    result = Table(wrapped, colWidths=widths, repeatRows=1)
    result.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0067B8")),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#CBD5E1")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F8FAFC")]),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return result


def footer(canvas, doc) -> None:
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#CBD5E1"))
    canvas.line(0.65 * inch, 0.48 * inch, 7.85 * inch, 0.48 * inch)
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(colors.HexColor("#475569"))
    canvas.drawString(0.65 * inch, 0.3 * inch, "Authenticated live tenant data only")
    canvas.drawRightString(7.85 * inch, 0.3 * inch, f"Page {doc.page}")
    canvas.restoreState()


def build_pdf() -> None:
    styles = getSampleStyleSheet()
    title = ParagraphStyle("Title", parent=styles["Title"], fontName="Helvetica-Bold", fontSize=23, leading=28, alignment=TA_CENTER, textColor=colors.HexColor("#0F172A"), spaceAfter=10)
    subtitle = ParagraphStyle("Subtitle", parent=styles["BodyText"], fontName="Helvetica", fontSize=11, leading=15, alignment=TA_CENTER, textColor=colors.HexColor("#334155"), spaceAfter=16)
    h1 = ParagraphStyle("H1", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=16, leading=20, textColor=colors.HexColor("#0067B8"), spaceBefore=8, spaceAfter=7)
    body = ParagraphStyle("Body", parent=styles["BodyText"], fontName="Helvetica", fontSize=9.4, leading=13.5, textColor=colors.HexColor("#1E293B"), spaceAfter=7, splitLongWords=True)
    header = ParagraphStyle("Header", parent=body, fontName="Helvetica-Bold", fontSize=8, leading=11, textColor=colors.white)
    cell = ParagraphStyle("Cell", parent=body, fontSize=8, leading=11, spaceAfter=0)
    callout = ParagraphStyle("Callout", parent=body, fontName="Helvetica-Bold", fontSize=10, leading=15, borderColor=colors.HexColor("#FDBA74"), borderWidth=1, borderPadding=10, backColor=colors.HexColor("#FFF7ED"), textColor=colors.HexColor("#7C2D12"), spaceBefore=8, spaceAfter=11)

    doc = SimpleDocTemplate(str(OUTPUT), pagesize=letter, rightMargin=0.65 * inch, leftMargin=0.65 * inch, topMargin=0.62 * inch, bottomMargin=0.62 * inch, title="Get Azure Advisor - Live Tenant Data Only", author="finops-reporting")

    story = [
        Spacer(1, 0.35 * inch),
        Paragraph("Get Azure Advisor", title),
        Paragraph("Copilot Studio configuration for authenticated live Azure tenant data only", subtitle),
        Paragraph("Every finding must come from Azure Monitor Logs under the signed-in user's delegated access. Static files, websites, skills, web search, and documentation tools are excluded.", callout),
        Paragraph("Target configuration", h1),
        table(
            [
                ["Area", "Required value"],
                ["Harness / Model", "GitHub Copilot harness (cliagent-1.0.0) / Claude Sonnet 5"],
                ["Authentication", "Authenticate with Microsoft; always authenticate; group-membership access"],
                ["Live tool", "Azure Monitor Logs > Run query and list results V2 (QueryDataV2)"],
                ["Tool identity", "User authentication; never Maker authentication for tenant analysis"],
                ["Knowledge / Skills", "None / None"],
                ["Other tools", "None"],
                ["Web search / Memory", "Off / Off"],
                ["Connected agents / Workflows", "None / None"],
                ["Moderation / Public website", "Medium / Not enabled"],
            ],
            [2.3 * inch, 4.85 * inch],
            cell,
            header,
        ),
        Paragraph("Configure", h1),
        bullets(
            [
                "Replace Build > Instructions with <b>agent-recreation/agent-instructions.md</b>.",
                "Keep only <b>Azure Monitor Logs > Run query and list results V2</b> under Tools.",
                "Set authentication to <b>User</b>. Keep Query and Time Range Type AI-filled; Timerange remains dynamic.",
                "Remove Microsoft Learn Docs MCP and every other non-tenant tool.",
                "Remove every uploaded file, website knowledge source, and skill.",
                "Keep web search and memory off. Do not add connected agents or workflows.",
                "Test with real authorized identities, then publish only to approved authenticated channels.",
            ],
            body,
        ),
        PageBreak(),
        Paragraph("Query context and evidence", h1),
        Paragraph("Before querying, request confirmation of the tenant context, subscription or Log Analytics workspace scope, and time range.", body),
        bullets(
            [
                "Identify Azure Monitor Logs as the source for every finding.",
                "State the confirmed scope and collection time.",
                "Ground conclusions in returned rows and redact unnecessary customer data.",
                "Keep denied, unavailable, unsupported, and empty results distinct.",
                "Do not interpret an empty result as zero spend, zero waste, or a healthy environment.",
            ],
            body,
        ),
        Paragraph("Stop condition", h1),
        Paragraph("If live tenant evidence is unavailable, stop the assessment, explain the missing access, scope, or data, and do not provide inferred recommendations or best-practice filler.", callout),
        Paragraph("Capability boundary", h1),
        Paragraph("The only live data source is Azure Monitor Logs. The agent has no Azure Advisor API, Cost Management API, Resource Graph, Power BI, Function App, custom API, or tenant-wide Azure MCP connection. Its name does not establish an Azure Advisor integration.", body),
        Paragraph("Validation", h1),
        bullets(
            [
                "Exactly one tool exists: Azure Monitor Logs QueryDataV2 with User authentication.",
                "Skills, static knowledge, web search, memory, connected agents, and workflows are absent or off.",
                "The agent requests tenant, scope, and time range before querying.",
                "Every finding includes source, scope, and collection time.",
                "The agent refuses to assess or recommend without live tenant evidence.",
                "No credentials, connection IDs, tenant IDs, subscription IDs, generated findings, or sample costs are committed.",
            ],
            body,
        ),
    ]

    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(OUTPUT)


if __name__ == "__main__":
    build_pdf()
