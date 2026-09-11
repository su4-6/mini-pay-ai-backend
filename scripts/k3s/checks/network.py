"""Pod-side TCP checks and RabbitMQ API authentication; JSON arrives on stdin."""
import base64
import json
import socket
import sys
import urllib.parse
import urllib.request


def main():
    config = json.load(sys.stdin)
    host = config['host']
    for name, port in config['ports'].items():
        try:
            with socket.create_connection((host, int(port)), timeout=5):
                pass
        except OSError:
            print('[FAIL] Pod TCP: ' + name)
            return 1
        print('[PASS] Pod TCP: ' + name)
    user = config['rabbit_user']
    auth = base64.b64encode((user + ':' + config['rabbit_password']).encode()).decode()
    url = 'http://{}:{}/api/permissions/%2F/{}'.format(
        host, config['rabbit_management_port'], urllib.parse.quote(user, safe=''))
    request = urllib.request.Request(url, headers={'Authorization': 'Basic ' + auth})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=10) as response:
            permissions = json.load(response)
        if any(permissions.get(key) != '.*' for key in ('configure', 'write', 'read')):
            print('[FAIL] RabbitMQ default vhost permissions')
            return 1
    except Exception:
        print('[FAIL] RabbitMQ management authentication or permissions')
        return 1
    print('[PASS] RabbitMQ API authentication and default vhost permissions')
    print('[PENDING] Business AMQP publish/consume, payment/TCC and durable data tests')
    return 0


if __name__ == '__main__':
    sys.exit(main())
