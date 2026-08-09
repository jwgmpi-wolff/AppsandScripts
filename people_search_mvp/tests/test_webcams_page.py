import importlib.util
from pathlib import Path
import unittest
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("people_search_webcam_entrypoint", ROOT / "app.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class WebcamsPageTests(unittest.TestCase):
    def test_webcams_page_filters_saved_urls(self):
        marker = uuid4().hex
        url = f"http://example.com/{marker}/cam.mjpg"
        connection = MODULE.get_conn()
        connection.execute(
            """
            INSERT INTO webcam_success(scan_id, url, status_code, content_type, note)
            VALUES(?, ?, ?, ?, ?)
            """,
            (None, url, 200, "image/jpeg", "camera"),
        )
        connection.commit()
        connection.close()

        try:
            with MODULE.app.test_client() as client:
                response = client.get(f"/webcams_page?q={marker}")

            self.assertEqual(response.status_code, 200)
            self.assertIn(url, response.get_data(as_text=True))
        finally:
            connection = MODULE.get_conn()
            connection.execute("DELETE FROM webcam_success WHERE url = ?", (url,))
            connection.commit()
            connection.close()


if __name__ == "__main__":
    unittest.main()
