#!/usr/bin/env python3
"""
Manual test script for POST /api/sync/upload endpoint
This script tests the multipart file upload functionality.
"""

import requests
import hashlib
import sys

def calculate_sha256(data):
    """Calculate SHA-256 hash of data"""
    return hashlib.sha256(data).hexdigest()

def test_single_chunk_upload():
    """Test uploading a file in a single chunk"""
    print("Testing single chunk upload...")
    
    url = "http://localhost:8789/api/sync/upload"
    
    # Test data
    file_content = b"Hello, this is a test file for upload endpoint!"
    checksum = calculate_sha256(file_content)
    
    # Prepare multipart form data
    files = {
        'path': (None, 'test_single.txt'),
        'chunk': (None, '0'),
        'totalChunks': (None, '1'),
        'checksum': (None, checksum),
        'data': ('data', file_content)
    }
    
    # You need to replace 'your-api-key-here' with the actual API key
    headers = {
        'Authorization': 'Bearer your-api-key-here'
    }
    
    try:
        response = requests.post(url, files=files, headers=headers)
        print(f"Status Code: {response.status_code}")
        print(f"Response: {response.json()}")
        
        if response.status_code == 200:
            data = response.json()
            assert data['uploaded'] == True
            assert data['complete'] == True
            assert data['receivedChunks'] == [0]
            print("✓ Single chunk upload test PASSED")
        else:
            print("✗ Single chunk upload test FAILED")
            return False
    except Exception as e:
        print(f"✗ Error: {e}")
        return False
    
    return True

def test_multi_chunk_upload():
    """Test uploading a file in multiple chunks"""
    print("\nTesting multi-chunk upload...")
    
    url = "http://localhost:8789/api/sync/upload"
    
    # Create test data - split into 3 chunks
    chunk0 = b"Part 1 of the file. "
    chunk1 = b"Part 2 of the file. "
    chunk2 = b"Part 3 of the file."
    
    full_content = chunk0 + chunk1 + chunk2
    checksum = calculate_sha256(full_content)
    
    headers = {
        'Authorization': 'Bearer your-api-key-here'
    }
    
    chunks = [chunk0, chunk1, chunk2]
    
    try:
        for i, chunk_data in enumerate(chunks):
            files = {
                'path': (None, 'test_multi.txt'),
                'chunk': (None, str(i)),
                'totalChunks': (None, '3'),
                'checksum': (None, checksum),
                'data': ('data', chunk_data)
            }
            
            response = requests.post(url, files=files, headers=headers)
            print(f"Chunk {i}: Status {response.status_code}")
            print(f"Response: {response.json()}")
            
            if response.status_code == 200:
                data = response.json()
                assert data['uploaded'] == True
                assert i in data['receivedChunks']
                
                if i == 2:  # Last chunk
                    assert data['complete'] == True
                    print("✓ Multi-chunk upload test PASSED")
                else:
                    assert data['complete'] == False
            else:
                print(f"✗ Multi-chunk upload test FAILED at chunk {i}")
                return False
                
    except Exception as e:
        print(f"✗ Error: {e}")
        return False
    
    return True

def test_binary_upload():
    """Test uploading binary data"""
    print("\nTesting binary data upload...")
    
    url = "http://localhost:8789/api/sync/upload"
    
    # Create binary data with various byte values
    binary_data = bytes(range(256))
    checksum = calculate_sha256(binary_data)
    
    files = {
        'path': (None, 'test_binary.dat'),
        'chunk': (None, '0'),
        'totalChunks': (None, '1'),
        'checksum': (None, checksum),
        'data': ('data', binary_data)
    }
    
    headers = {
        'Authorization': 'Bearer your-api-key-here'
    }
    
    try:
        response = requests.post(url, files=files, headers=headers)
        print(f"Status Code: {response.status_code}")
        print(f"Response: {response.json()}")
        
        if response.status_code == 200:
            data = response.json()
            assert data['uploaded'] == True
            assert data['complete'] == True
            print("✓ Binary upload test PASSED")
        else:
            print("✗ Binary upload test FAILED")
            return False
    except Exception as e:
        print(f"✗ Error: {e}")
        return False
    
    return True

def test_authentication():
    """Test that authentication is required"""
    print("\nTesting authentication requirement...")
    
    url = "http://localhost:8789/api/sync/upload"
    
    files = {
        'path': (None, 'test.txt'),
        'chunk': (None, '0'),
        'totalChunks': (None, '1'),
        'checksum': (None, 'abc123'),
        'data': ('data', b'test')
    }
    
    # No Authorization header
    try:
        response = requests.post(url, files=files)
        print(f"Status Code: {response.status_code}")
        
        if response.status_code == 401:
            print("✓ Authentication test PASSED")
            return True
        else:
            print("✗ Authentication test FAILED - expected 401")
            return False
    except Exception as e:
        print(f"✗ Error: {e}")
        return False

def main():
    print("=" * 60)
    print("POST /api/sync/upload Endpoint Test Suite")
    print("=" * 60)
    print("\nIMPORTANT: Replace 'your-api-key-here' with the actual API key")
    print("You can get the API key from the ForgeHttpServer on the device\n")
    
    results = []
    
    # Run tests
    results.append(("Authentication", test_authentication()))
    results.append(("Single Chunk Upload", test_single_chunk_upload()))
    results.append(("Multi-Chunk Upload", test_multi_chunk_upload()))
    results.append(("Binary Upload", test_binary_upload()))
    
    # Summary
    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)
    
    passed = sum(1 for _, result in results if result)
    total = len(results)
    
    for name, result in results:
        status = "PASSED" if result else "FAILED"
        symbol = "✓" if result else "✗"
        print(f"{symbol} {name}: {status}")
    
    print(f"\nTotal: {passed}/{total} tests passed")
    
    if passed == total:
        print("\n🎉 All tests passed!")
        return 0
    else:
        print(f"\n❌ {total - passed} test(s) failed")
        return 1

if __name__ == "__main__":
    sys.exit(main())
