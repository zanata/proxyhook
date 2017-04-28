#!/bin/bash
url='http://localhost:8080/add-item/?site=mySiteName&apiKey=12345&type=contentTypeName'
echo "url: $url"
payload='{"publish_date": "now", "name" : "Name of Item"}'
echo "payload: $payload"
curl -siX POST -H "Content-Type: application/json" -d "$payload" "$url"

