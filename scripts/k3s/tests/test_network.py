"""Check the diagnostic's success/failure semantics without a real network."""
import contextlib
import importlib.util
import io
import json
import pathlib
import unittest
from unittest.mock import MagicMock, patch

path = pathlib.Path(__file__).parents[1] / 'checks' / 'network.py'
spec = importlib.util.spec_from_file_location('network_check', path)
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)


class NetworkCheckTest(unittest.TestCase):
    def run_check(self, *, connect_error=None, permissions=None, auth_error=None):
        config = {'host': 'infra.invalid', 'ports': {'RabbitMQ': 5672, 'Seata': 8091},
                  'rabbit_user': 'test', 'rabbit_password': 'DO_NOT_PRINT',
                  'rabbit_management_port': 15672}
        response = io.StringIO(json.dumps(permissions or {'configure': '.*', 'write': '.*', 'read': '.*'}))
        opener = MagicMock()
        if auth_error:
            opener.open.side_effect = auth_error
        else:
            opener.open.return_value.__enter__.return_value = response
        output = io.StringIO()
        with patch.object(network.sys, 'stdin', io.StringIO(json.dumps(config))), \
                patch.object(network.socket, 'create_connection', side_effect=connect_error), \
                patch.object(network.urllib.request, 'build_opener', return_value=opener), \
                contextlib.redirect_stdout(output):
            code = network.main()
        self.assertNotIn('DO_NOT_PRINT', output.getvalue())
        return code, output.getvalue()

    def test_success_keeps_business_validation_pending(self):
        code, output = self.run_check()
        self.assertEqual(code, 0)
        self.assertIn('[PENDING]', output)

    def test_tcp_failure_is_failure(self):
        self.assertEqual(self.run_check(connect_error=OSError('unreachable'))[0], 1)

    def test_wrong_credentials_are_failure_without_exception_leak(self):
        self.assertEqual(self.run_check(auth_error=ValueError('DO_NOT_PRINT'))[0], 1)

    def test_restricted_vhost_permissions_are_failure(self):
        self.assertEqual(self.run_check(permissions={'configure': '', 'read': '', 'write': ''})[0], 1)


if __name__ == '__main__':
    unittest.main()
