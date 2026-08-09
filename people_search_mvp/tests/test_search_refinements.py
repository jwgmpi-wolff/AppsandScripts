import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("people_search_entrypoint", ROOT / "app.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SearchRefinementTests(unittest.TestCase):
    def test_all_sources_searches_every_provider_and_configured_scope(self):
        calls = []

        def provider(name):
            def fetch(query, max_results):
                calls.append((name, query, max_results))
                return {
                    "results": [{
                        "title": f"{name} result",
                        "url": f"https://{name}.example/{len(calls)}",
                        "snippet": query,
                        "source": name,
                    }],
                    "error": None,
                }
            return fetch

        providers = {
            "duckduckgo": provider("duckduckgo"),
            "brave": provider("brave"),
            "wikipedia": provider("wikipedia"),
        }
        with patch.object(MODULE, "PROVIDERS", providers):
            results, errors = MODULE.fetch_results("all_source_scopes", '"Jane Doe"', 25)

        expected_search_scopes = 3 + len(MODULE.SOCIAL_MEDIA_DOMAINS)
        self.assertEqual(len(calls), expected_search_scopes * 2 + 1)
        self.assertEqual(len(results), len(calls))
        self.assertEqual(errors, [])
        for provider_name in ("duckduckgo", "brave", "wikipedia"):
            self.assertTrue(any(call[0] == provider_name for call in calls))
        for domain in MODULE.SOCIAL_MEDIA_DOMAINS.values():
            self.assertTrue(any(f"site:{domain}" in call[1] for call in calls))
        self.assertTrue(any("site:.gov OR site:.us" in call[1] for call in calls))
        self.assertTrue(any('"news" OR "media"' in call[1] for call in calls))

    def test_duckduckgo_uses_lite_post_and_parses_records(self):
        html = """
        <table>
          <tr><td>1.</td><td><a class="result-link" href="https://records.example.gov/raul">Raul Alexis record</a></td></tr>
          <tr><td></td><td class="result-snippet">Raul&amp;#39;s Florida public record.</td></tr>
        </table>
        """
        response = type("Response", (), {"text": html})()

        with patch.object(MODULE, "safe_request", return_value=(response, None)) as request:
            result = MODULE.fetch_duckduckgo('"Raul Alexis" Florida', 25)

        self.assertEqual(len(result["results"]), 1)
        self.assertEqual(result["results"][0]["snippet"], "Raul's Florida public record.")
        self.assertEqual(request.call_args.kwargs["method"], "post")
        self.assertIn("lite.duckduckgo.com", request.call_args.args[0])

    def test_search_applies_all_refinements_to_provider_query(self):
        candidates = [
            {
                "title": "Zoe Adams - Profile",
                "url": "https://example.test/zoe",
                "snippet": "",
                "source": "test",
            }
        ]

        enriched = [
            {
                **candidates[0],
                "score": 1,
                "record": {
                    "name": "Zoe Adams",
                    "summary": "Extracted public record.",
                    "fields": [{"label": "Record ID", "value": "ABC-123"}],
                    "source_name": "example.test",
                    "extraction": "page",
                },
            }
        ]
        with MODULE.app.test_client() as client, patch.object(
            MODULE, "fetch_results", return_value=(candidates, [])
        ) as fetch_results, patch.object(
            MODULE, "enrich_search_results", return_value=enriched
        ):
            response = client.post(
                "/search",
                data={
                    "query": "software engineer",
                    "entity": "public",
                    "source": "public_records",
                    "country": "United States",
                    "state": "Washington",
                    "county": "King County",
                    "city": "Seattle",
                    "public_record_type": "assessor",
                    "social_media_site": "linkedin",
                    "sort_by": "last_name",
                    "max_results": "75",
                },
            )

        self.assertEqual(response.status_code, 200)
        launched_query = fetch_results.call_args.args[1]
        for expected in (
            '"software engineer"',
            "Seattle",
            "King County",
            "Washington",
            "United States",
            "county assessor",
            "site:.gov",
            "site:linkedin.com",
        ):
            self.assertIn(expected, launched_query)
        self.assertNotIn('"Seattle, King County, Washington, United States"', launched_query)
        self.assertEqual(
            response.get_json()["results"][0]["record"]["fields"][0]["value"],
            "ABC-123",
        )
        self.assertNotIn("url", response.get_json()["results"][0])
        record_path = response.get_json()["results"][0]["record_path"]
        self.assertRegex(record_path, r"^/records/\d+$")
        self.assertEqual(fetch_results.call_args.args[2], 75)

        with MODULE.app.test_client() as client:
            detail = client.get(record_path)
            source = client.get(f"{record_path}/source")

        self.assertEqual(detail.status_code, 200)
        self.assertIn("ABC-123", detail.get_data(as_text=True))
        self.assertEqual(source.status_code, 302)
        self.assertEqual(source.headers["Location"], "https://example.test/zoe")

    def test_search_accepts_first_and_last_name_filters_without_query(self):
        with MODULE.app.test_client() as client, patch.object(
            MODULE, "fetch_results", return_value=([], [])
        ) as fetch_results:
            response = client.post(
                "/search",
                data={
                    "query": "",
                    "first_name": "Avery",
                    "last_name": "Stonebridge",
                    "entity": "public",
                    "country": "United States",
                },
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["query"], "Avery Stonebridge")
        self.assertTrue(fetch_results.call_args.args[1].startswith('"Avery Stonebridge"'))
        self.assertIn("United States", fetch_results.call_args.args[1])

    def test_search_requires_query_or_name_filter(self):
        with MODULE.app.test_client() as client:
            response = client.post(
                "/search",
                data={"query": "", "first_name": "", "last_name": ""},
            )

        self.assertEqual(response.status_code, 400)
        self.assertIn("first name", response.get_json()["error"])

    def test_extracts_structured_data_from_record_page(self):
        html = """
        <html><head>
          <script type="application/ld+json">
            {"@type":"Person","name":"Jane Doe","jobTitle":"Engineer","description":"Public profile details."}
          </script>
        </head><body>
          <table><tr><th>Record ID</th><td>ABC-123</td></tr></table>
        </body></html>
        """

        record = MODULE.extract_record_from_html(
            html,
            {
                "title": "Jane Doe - Profile",
                "url": "https://records.example.gov/jane",
                "snippet": "Excerpt",
            },
        )

        self.assertEqual(record["name"], "Jane Doe")
        self.assertEqual(record["summary"], "Public profile details.")
        self.assertIn({"label": "Occupation", "value": "Engineer"}, record["fields"])
        self.assertIn({"label": "Record ID", "value": "ABC-123"}, record["fields"])
        self.assertEqual(MODULE._record_value("Raul&#39;s record"), "Raul's record")

        excerpt = MODULE.extract_record_from_html(
            "",
            {"title": "Raul Alexis", "snippet": "Raul&#39;s public record", "url": ""},
        )
        self.assertEqual(excerpt["summary"], "Raul's public record")

    def test_extracts_explicit_employment_and_financial_highlights(self):
        record = MODULE.extract_record_from_html(
            "",
            {
                "title": "Raul Alexis Martin - Public Profile",
                "url": "https://example.test/raul",
                "snippet": (
                    "Experience: Raulito's Cuban Sandwiches · Employment status: Employed. "
                    "View profile | $200 - $249,999 Net Worth"
                ),
            },
        )

        self.assertIn(
            {"label": "Organization", "value": "Raulito's Cuban Sandwiches"},
            record["fields"],
        )
        self.assertIn(
            {"label": "Employment status", "value": "Employed"},
            record["fields"],
        )
        self.assertIn(
            {"label": "Estimated net worth (third-party)", "value": "$200 - $249,999"},
            record["fields"],
        )

        structured = MODULE.extract_record_from_html(
            '<script type="application/ld+json">'
            '{"@type":"Person","name":"Jane Doe","worksFor":{"@type":"Organization","name":"Example Co"}}'
            "</script>",
            {"title": "Jane Doe", "url": "https://example.test/jane", "snippet": ""},
        )
        self.assertIn({"label": "Organization", "value": "Example Co"}, structured["fields"])

        linkedin = MODULE.extract_record_from_html(
            "",
            {
                "title": "Raul Alexis Alvarez | LinkedIn",
                "url": "https://linkedin.com/in/raul",
                "snippet": (
                    "Experience: Raulito's Cuban Sandwiches · Location: Miami. "
                    "View this profile on LinkedIn, a professional community of 1 billion members."
                ),
            },
        )
        self.assertNotIn("Occupation", [field["label"] for field in linkedin["fields"]])

    def test_enriches_named_person_results_with_indexed_public_details(self):
        results = [
            {
                "title": "Philippe K Debrosse - Miami, FL",
                "url": "https://www.mylife.com/philippe-debrosse/example",
                "snippet": "Philippe Debrosse lives in Miami, FL.",
                "source": "duckduckgo",
            }
        ]
        detail_snippet = (
            "Philippe is now married. Philippe's personal network of family, friends, "
            "associates & neighbors include Dominique Debrosse and Elisabeth Delatour. "
            "Philippe's net worth is greater than $250,000 - $499,999; and makes "
            "between $200 - 249,999 a year."
        )
        detail_response = {
            "results": [{**results[0], "snippet": detail_snippet}],
            "error": None,
        }

        with patch.object(MODULE, "fetch_duckduckgo", return_value=detail_response) as fetch:
            enriched, error = MODULE.enrich_person_search_excerpts("Philippe Debrosse", results, 25)

        self.assertIsNone(error)
        self.assertIn("net worth", enriched[0]["snippet"])
        self.assertEqual(fetch.call_args.args[0], '"Philippe Debrosse" net worth income employment')

        record = MODULE.extract_record_from_html("", enriched[0])
        self.assertIn(
            {"label": "Relationship status (third-party)", "value": "married"},
            record["fields"],
        )
        self.assertIn(
            {"label": "Associates (third-party)", "value": "Dominique Debrosse and Elisabeth Delatour"},
            record["fields"],
        )
        self.assertIn(
            {"label": "Estimated net worth (third-party)", "value": "$250,000 - $499,999"},
            record["fields"],
        )
        self.assertIn(
            {"label": "Annual income (third-party)", "value": "$200 - 249,999"},
            record["fields"],
        )

    def test_results_can_sort_by_first_or_last_name(self):
        results = [
            {"title": "Zoe Adams - Profile", "score": 2},
            {"title": "Amy Young - Profile", "score": 5},
        ]

        by_first = MODULE.sort_search_results(results, "first_name")
        by_last = MODULE.sort_search_results(results, "last_name")

        self.assertEqual(
            [item["title"] for item in by_first],
            ["Amy Young - Profile", "Zoe Adams - Profile"],
        )
        self.assertEqual(
            [item["title"] for item in by_last],
            ["Zoe Adams - Profile", "Amy Young - Profile"],
        )

    def test_home_page_exposes_refinement_dropdowns(self):
        with MODULE.app.test_client() as client:
            html = client.get("/").get_data(as_text=True)

        for field_name in (
            "first_name",
            "last_name",
            "public_record_type",
            "social_media_site",
            "country",
            "state",
            "county",
            "city",
            "sort_by",
        ):
            self.assertIn(f'name="{field_name}"', html)
        self.assertNotIn("Open source", html)
        self.assertIn("record.fields", html)
        self.assertIn("item.record_path", html)
        self.assertIn("View full record", html)
        self.assertIn("All public sources (exhaustive)", html)
        self.assertIn("diagnostics.provider_query_count", html)
        self.assertIn('id="clear-results"', html)
        self.assertIn("sessionStorage.setItem(searchResultsStorageKey", html)
        self.assertIn("restoreSearchResults();", html)
        self.assertIn("window.addEventListener('pageshow', restoreSearchResults);", html)
        self.assertIn("if (currentSearchResults) saveSearchResults(currentSearchResults);", html)
        self.assertNotIn("status.textContent = 'Searching…';\n      resultList.innerHTML = '';", html)
        self.assertIn('name="max_results" type="number" min="5" max="100" value="25"', html)

    def test_record_detail_returns_to_preserved_results(self):
        with MODULE.app.test_client() as client:
            MODULE.init_db()
            conn = MODULE.get_conn()
            search_cursor = conn.execute(
                "INSERT INTO searches(query, filters, result_count) VALUES(?, ?, ?)",
                ("Jane Doe", "{}", 1),
            )
            cursor = conn.execute(
                """
                INSERT INTO search_results(search_id, title, url, snippet, source, score, record_json)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    search_cursor.lastrowid,
                    "Jane Doe",
                    "https://example.test/jane",
                    "Public record",
                    "test",
                    1,
                    '{"name":"Jane Doe","fields":[]}',
                ),
            )
            result_id = cursor.lastrowid
            conn.commit()
            conn.close()

            html = client.get(f"/records/{result_id}").get_data(as_text=True)

        self.assertIn('href="/#results"', html)
        self.assertIn("history.back()", html)


if __name__ == "__main__":
    unittest.main()
