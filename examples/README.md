# Workstation test kit

Try every sink without real SIEM accounts. `receivers.py` stands in for Splunk HEC, the Sentinel token and upload
endpoints, a webhook, a syslog receiver (TCP 15514, octet counting) and a QRadar syslog receiver (TCP 15515,
newline framing). It checks the credentials from `nuxeo.conf.example` and appends what it receives to
`<dir>/<sink>.jsonl`.

```sh
python3 receivers.py /tmp/audit-out
docker run -d --rm -p 127.0.0.1:4566:4566 -e SERVICES=sqs -e LOCALSTACK_AUTH_TOKEN localstack/localstack:2026.09.0
curl -X POST http://127.0.0.1:4566/ -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'Authorization: AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=x' \
  -d 'Action=CreateQueue&QueueName=nuxeo-audit&Version=2012-11-05'
cat nuxeo.conf.example >> $NUXEO_HOME/bin/nuxeo.conf   # then restart Nuxeo
```

Stop `receivers.py` while Nuxeo runs, edit a document, start it again: the events created meanwhile arrive once the
receivers are back. These stand-ins validate the wire format and authentication, not the behavior of the real
products.
