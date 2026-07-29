from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates

BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))

app = FastAPI(
    title="Wolff Live Cams",
    version="1.0.0",
    description="Starter app for local run and Azure App Service deployment.",
)


@app.get("/", response_class=HTMLResponse)
def home(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "project": "Wolff Live Cams",
            "status": "ready",
        },
    )


@app.get("/health")
def health() -> JSONResponse:
    return JSONResponse({"status": "ok"})
