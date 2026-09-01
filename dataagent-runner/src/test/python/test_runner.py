import json
import os
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


RUNNER = Path(__file__).resolve().parents[3] / "bin" / "dataagent-runner"


class McpState:
    def __init__(self):
        self.requests = []
        self.mode = "json"
        self.call_result = {
            "content": [{"type": "text", "text": "validated"}],
            "isError": False,
        }


class McpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length))
        self.server.state.requests.append(
            {
                "path": self.path,
                "payload": payload,
                "session": self.headers.get("Mcp-Session-Id"),
                "protocol": self.headers.get("MCP-Protocol-Version"),
            }
        )
        method = payload.get("method")
        if method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "initialize":
            result = {
                "protocolVersion": "2025-06-18",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "mock", "version": "1"},
            }
        elif method == "tools/list":
            if payload.get("params", {}).get("cursor") == "page-2":
                result = {
                    "tools": [
                        {
                            "name": "validate_table",
                            "description": "Validate a table",
                            "inputSchema": {"type": "object"},
                        }
                    ]
                }
            else:
                result = {
                    "tools": [
                        {
                            "name": "upload_table",
                            "description": "Upload a table",
                            "inputSchema": {"type": "object"},
                        }
                    ],
                    "nextCursor": "page-2",
                }
        elif method == "tools/call":
            result = self.server.state.call_result
        else:
            self._respond(payload.get("id"), error={"code": -32601, "message": "unknown"})
            return
        self._respond(payload.get("id"), result=result)

    def _respond(self, request_id, result=None, error=None):
        response = {"jsonrpc": "2.0", "id": request_id}
        if error is None:
            response["result"] = result
        else:
            response["error"] = error
        encoded = json.dumps(response).encode()
        if self.server.state.mode == "sse":
            encoded = b"event: message\n" + b"data: " + encoded + b"\n\n"
            content_type = "text/event-stream"
        else:
            content_type = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Mcp-Session-Id", "session-1")
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, _format, *_args):
        pass


class DataAgentRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.state = McpState()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), McpHandler)
        cls.server.state = cls.state
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.state.requests.clear()
        self.state.mode = "json"
        self.state.call_result = {
            "content": [{"type": "text", "text": "validated"}],
            "isError": False,
        }

    def run_runner(self, *arguments, input_text=None, environment=True):
        env = os.environ.copy()
        if environment:
            env["POD_IP"] = "127.0.0.1"
            env["POD_PORT"] = str(self.server.server_port)
        else:
            env.pop("POD_IP", None)
            env.pop("POD_PORT", None)
        return subprocess.run(
            ["/bin/sh", str(RUNNER), *arguments],
            input=input_text,
            text=True,
            capture_output=True,
            env=env,
            timeout=10,
            check=False,
        )

    def test_help_and_version_do_not_require_network_configuration(self):
        help_result = self.run_runner("--help", environment=False)
        version_result = self.run_runner("--version", environment=False)
        self.assertEqual(0, help_result.returncode)
        self.assertIn("dataagent-runner --list", help_result.stdout)
        self.assertEqual(0, version_result.returncode)
        self.assertEqual("0.1.0-SNAPSHOT", version_result.stdout.strip())
        self.assertEqual([], self.state.requests)

    def test_invalid_environment_is_rejected_before_network_access(self):
        missing = self.run_runner("--list", environment=False)
        self.assertEqual(3, missing.returncode)
        self.assertIn("POD_IP", missing.stderr)
        env = os.environ.copy()
        env.update(POD_IP="bff.local", POD_PORT="invalid")
        invalid = subprocess.run(
            ["/bin/sh", str(RUNNER), "--list"], env=env, text=True,
            capture_output=True, timeout=10, check=False)
        self.assertEqual(3, invalid.returncode)
        self.assertIn("valid IPv4 or IPv6", invalid.stderr)
        self.assertEqual([], self.state.requests)

    def test_invalid_cli_and_non_object_json_return_usage_error(self):
        missing = self.run_runner(environment=False)
        malformed = self.run_runner("validate_table", "{", environment=False)
        scalar = self.run_runner("validate_table", '"value"', environment=False)
        extra = self.run_runner("tool", "{}", "extra", environment=False)
        self.assertEqual([2, 2, 2, 2], [
            missing.returncode, malformed.returncode, scalar.returncode, extra.returncode])
        self.assertIn("Usage:", missing.stderr)
        self.assertIn("valid JSON", malformed.stderr)
        self.assertIn("JSON object", scalar.stderr)
        self.assertEqual([], self.state.requests)

    def test_list_initializes_session_follows_pagination_and_sorts_tools(self):
        result = self.run_runner("--list")
        self.assertEqual(0, result.returncode, result.stderr)
        tools = json.loads(result.stdout)
        self.assertEqual(["upload_table", "validate_table"], [tool["name"] for tool in tools])
        methods = [request["payload"]["method"] for request in self.state.requests]
        self.assertEqual(
            ["initialize", "notifications/initialized", "tools/list", "tools/list"],
            methods,
        )
        self.assertIsNone(self.state.requests[0]["session"])
        self.assertEqual("session-1", self.state.requests[1]["session"])
        self.assertEqual("2025-06-18", self.state.requests[1]["protocol"])
        self.assertEqual("page-2", self.state.requests[3]["payload"]["params"]["cursor"])
        self.assertTrue(all(request["path"] == "/rest/mcp/script" for request in self.state.requests))

    def test_call_prechecks_tool_and_preserves_same_named_header_and_body_arguments(self):
        arguments = {"headerA": "header-value", "A": "body-value", "catalog": "analytics"}
        result = self.run_runner("validate_table", json.dumps(arguments))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(result.stdout)["isError"])
        call = self.state.requests[-1]
        self.assertEqual("tools/call", call["payload"]["method"])
        self.assertEqual("validate_table", call["payload"]["params"]["name"])
        self.assertEqual(arguments, call["payload"]["params"]["arguments"])
        self.assertEqual("session-1", call["session"])

    def test_call_reads_arguments_from_standard_input(self):
        result = self.run_runner("validate_table", "-", input_text='{"catalog":"stdin"}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"catalog": "stdin"},
            self.state.requests[-1]["payload"]["params"]["arguments"],
        )

    def test_missing_tool_returns_five_without_sending_tool_call(self):
        result = self.run_runner("unknown_tool", "{}")
        self.assertEqual(5, result.returncode)
        self.assertIn("is not available", result.stderr)
        self.assertNotIn("tools/call", [request["payload"]["method"] for request in self.state.requests])

    def test_tool_level_error_is_printed_and_returns_six(self):
        self.state.call_result = {
            "content": [{"type": "text", "text": "validation failed"}],
            "isError": True,
        }
        result = self.run_runner("validate_table", "{}")
        self.assertEqual(6, result.returncode)
        self.assertTrue(json.loads(result.stdout)["isError"])

    def test_sse_transport_responses_are_supported(self):
        self.state.mode = "sse"
        result = self.run_runner("validate_table", '{"catalog":"sse"}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("validated", json.loads(result.stdout)["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
