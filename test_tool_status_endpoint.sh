#!/bin/bash
# Manual test script for GET /api/tool/{opId}/status endpoint
# This script demonstrates how to use the endpoint

echo "Testing GET /api/tool/{opId}/status endpoint"
echo "=============================================="
echo ""
echo "Prerequisites:"
echo "1. ForgeHttpServer must be running on the device"
echo "2. You need a valid Bearer token"
echo "3. An operation must be registered in ToolExecutionManager"
echo ""
echo "Example usage with curl:"
echo ""
echo "# Test with a non-existent operation (should return 404)"
echo 'curl -X GET "http://localhost:8789/api/tool/test-op-123/status" \'
echo '     -H "Authorization: Bearer YOUR_API_KEY_HERE"'
echo ""
echo "# Expected response for non-existent operation:"
echo '{"error":"operation not found"}'
echo ""
echo "# Expected response for a pending operation:"
echo '{'
echo '  "op_id": "test-op-123",'
echo '  "tool_name": "file_read",'
echo '  "status": "pending",'
echo '  "start_time": 1703001000000'
echo '}'
echo ""
echo "# Expected response for a running operation with progress:"
echo '{'
echo '  "op_id": "test-op-123",'
echo '  "tool_name": "web_search",'
echo '  "status": "running",'
echo '  "start_time": 1703001000000,'
echo '  "progress": {'
echo '    "percent": 45,'
echo '    "message": "Searching..."'
echo '  }'
echo '}'
echo ""
echo "# Expected response for a completed operation:"
echo '{'
echo '  "op_id": "test-op-123",'
echo '  "tool_name": "calculate",'
echo '  "status": "completed",'
echo '  "start_time": 1703001000000,'
echo '  "end_time": 1703001150000,'
echo '  "output": "Result: 42",'
echo '  "resource_usage": {'
echo '    "cpu_ms": 1500,'
echo '    "memory_bytes": 1024000'
echo '  }'
echo '}'
