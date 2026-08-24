# Technical Design Document: Forge Desktop Integration

## 1. Overview

This document describes the technical architecture for the Forge Desktop Integration feature, which establishes bidirectional communication between Forge OS (Android) and Forge Desktop (Tauri application). The design extends the existing HTTP API with WebSocket-based event streaming, enhanced connection management, file synchronization, clipboard sharing, and notification bridging capabilities.

## 2. System Architecture

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Forge Desktop (Tauri)                   │
├─────────────────────────────────────────────────────────────┤
│  React UI Layer                                             │
│  ├── Connection Manager UI                                  │
│  ├── Tool Executor UI                                       │
│  ├── Agent Chat UI                                          │
│  └── Monitoring Dashboard                                   │
├─────────────────────────────────────────────────────────────┤
│  TypeScript Service Layer                                   │
│  ├── ConnectionManager                                      │
│  ├── HealthMonitor                                          │
│  ├── EventStreamClient                                      │
│  ├── ToolValidator                                          │
│  ├── ProfileManager                                         │
│  └── MetricsCollector                                       │
├─────────────────────────────────────────────────────────────┤
│  Rust Backend (Tauri Commands)                             │
│  ├── Discovery Service (mDNS/UDP)                          │
│  ├── Connection Transport (TCP/ADB/Relay)                  │
│  ├── Sync Engine                                            │
│  ├── Clipboard Manager                                      │
│  ├── Desktop Tool Registry                                  │
│  └── Secure Storage (Keychain/CredentialStore)             │
└─────────────────────────────────────────────────────────────┘
                            ↕
          HTTP/1.1 + WebSocket over TCP/USB/Relay
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                   Forge OS (Android)                        │
├─────────────────────────────────────────────────────────────┤
│  ForgeHttpServer (Ktor)                                     │
│  ├── /api/status (GET)                                      │
│  ├── /api/tools (GET)                                       │
│  ├── /api/tool (POST)                                       │
│  ├── /api/tool/{opId}/status (GET) [NEW]                   │
│  ├── /api/tool/{opId}/cancel (POST) [NEW]                  │
│  ├── /api/chat (POST)                                       │
│  ├── /api/pairing/initiate (POST) [NEW]                    │
│  ├── /api/pairing/confirm (POST) [NEW]                     │
│  ├── /api/sync/upload (POST) [NEW]                         │
│  ├── /api/sync/download (GET) [NEW]                        │
│  ├── /api/clipboard (POST) [NEW]                           │
│  ├── /api/config (GET/POST) [NEW]                          │
│  └── /api/events (WebSocket) [NEW]                         │
├─────────────────────────────────────────────────────────────┤
│  Service Layer                                              │
│  ├── ToolExecutionManager                                   │
│  ├── SessionManager                                         │
│  ├── PairingService                                         │
│  ├── SyncService                                            │
│  ├── ClipboardService                                       │
│  ├── NotificationListenerService                           │
│  ├── EventBroadcaster                                       │
│  └── DesktopToolInvoker                                     │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Component Breakdown

#### Desktop Components

1. **ConnectionManager** (TypeScript)
   - Manages device connections and profiles
   - Handles connection lifecycle (connect, disconnect, reconnect)
   - Implements connection fallback strategies (TCP → ADB → Relay)
   - Stores encrypted connection profiles

2. **DiscoveryService** (Rust)
   - mDNS/UDP broadcast for device discovery
   - Announces service on `_forgeos._tcp.local`
   - Parses TXT records for device metadata
   - ADB device enumeration via `adb devices`

3. **HealthMonitor** (TypeScript)
   - Polls `/api/status` every 10 seconds
   - Tracks latency, connection state, and error rates
   - Implements exponential backoff for retries
   - Emits connection state change events

4. **EventStreamClient** (TypeScript)
   - WebSocket client for `/api/events`
   - Handles authentication via Connection-Token
   - Implements automatic reconnection with exponential backoff
   - Supports event filtering and subscription management

5. **SyncEngine** (Rust)
   - File watcher for monitored directories
   - Chunked file uploads with resumption support
   - SHA-256 checksum verification
   - Conflict detection and resolution

6. **ClipboardManager** (Rust)
   - Monitors system clipboard via platform APIs
   - Debounces clipboard changes (500ms)
   - Compresses large clipboard content
   - Encrypts data with AES-256-GCM

7. **ToolValidator** (TypeScript)
   - JSON Schema validation for tool parameters
   - TypeScript type generation from schemas
   - Runtime type checking with Zod

8. **Desktop Tool Registry** (Rust)
   - Registers desktop-native tools
   - Executes tool invocations from device
   - Handles confirmation dialogs for sensitive tools

#### Android Components

9. **ForgeHttpServer Extensions** (Kotlin)
   - WebSocket handler for `/api/events`
   - Async tool execution with operation IDs
   - Pairing flow with 6-digit confirmation codes
   - File upload/download endpoints

10. **ToolExecutionManager** (Kotlin)
    - Tracks in-progress tool executions
    - Provides progress updates via EventBroadcaster
    - Supports cancellation via coroutine cancellation
    - Returns structured error information

11. **EventBroadcaster** (Kotlin)
    - Broadcasts events to all connected WebSocket clients
    - Event types: ToolStart, ToolProgress, ToolComplete, AgentTurn, FileModified, NotificationReceived
    - Implements event filtering per client subscription

12. **PairingService** (Kotlin)
    - Generates 6-digit pairing codes
    - Validates pairing codes with time-based expiration
    - Issues JWT-based connection tokens
    - Stores tokens in SecureKeyStore

13. **SyncService** (Kotlin)
    - Handles file uploads to workspace
    - Serves file downloads with range support
    - Notifies EventBroadcaster on file changes

14. **ClipboardService** (Kotlin)
    - Monitors Android clipboard
    - Updates clipboard from desktop events
    - Integrates with ClipboardManager API

15. **NotificationListenerService** (Kotlin)
    - Listens to StatusBarNotification events
    - Filters notifications based on user preferences
    - Sends notification metadata to EventBroadcaster

## 3. Data Models

### 3.1 Connection Profile

```typescript
interface ConnectionProfile {
  id: string;              // UUID
  name: string;            // User-defined name
  deviceId: string;        // Device UUID from Android
  host: string;            // IP or hostname
  port: number;            // Default 8789
  token: string;           // Encrypted Bearer token
  connectionMethod: 'tcp' | 'adb' | 'relay';
  lastConnected: number;   // Unix timestamp
  deviceMetadata: {
    model: string;
    androidVersion: string;
    forgeOsVersion: string;
    capabilities: string[];
  };
}
```

### 3.2 Event Stream Message

```typescript
interface EventMessage {
  type: 'tool_start' | 'tool_progress' | 'tool_complete' | 'tool_error' |
        'agent_turn' | 'file_modified' | 'notification' | 'clipboard' |
        'config_changed' | 'desktop_tool_invoke';
  timestamp: number;
  payload: unknown;
}

interface ToolStartEvent {
  opId: string;
  toolName: string;
  args: Record<string, unknown>;
}

interface ToolProgressEvent {
  opId: string;
  percent: number;
  message?: string;
}

interface ToolCompleteEvent {
  opId: string;
  output: string;
  duration: number;
  resourceUsage: {
    cpuMs: number;
    memoryBytes: number;
  };
}

interface NotificationEvent {
  id: string;
  packageName: string;
  title: string;
  body: string;
  icon?: string; // Base64 encoded
  actions: Array<{ id: string; label: string }>;
}

interface DesktopToolInvokeEvent {
  invokeId: string;
  toolName: string;
  args: Record<string, unknown>;
  timeout: number;
}
```

### 3.3 Tool Operation

```typescript
interface ToolOperation {
  opId: string;
  toolName: string;
  args: Record<string, unknown>;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
  startTime: number;
  endTime?: number;
  progress?: {
    percent: number;
    message?: string;
  };
  result?: string;
  error?: {
    code: string;
    message: string;
    stackTrace?: string;
  };
}
```

### 3.4 Sync File Entry

```kotlin
data class SyncFileEntry(
    val path: String,
    val checksum: String,      // SHA-256
    val size: Long,
    val lastModified: Long,
    val chunkCount: Int,       // For resumable uploads
    val uploadedChunks: Set<Int>
)
```

### 3.5 Clipboard Entry

```typescript
interface ClipboardEntry {
  type: 'text' | 'image' | 'file';
  content?: string;          // For text
  imageData?: ArrayBuffer;   // For PNG/JPEG
  fileName?: string;         // For file references
  timestamp: number;
}
```

## 4. API Specifications

### 4.1 New HTTP Endpoints (Android)

#### POST /api/pairing/initiate
Generate a pairing code for a new desktop client.

**Request:**
```json
{
  "desktop_name": "John's MacBook"
}
```

**Response:**
```json
{
  "pairing_code": "123456",
  "expires_in": 300
}
```

#### POST /api/pairing/confirm
Confirm pairing with the code and receive a connection token.

**Request:**
```json
{
  "pairing_code": "123456",
  "desktop_id": "uuid-v4"
}
```

**Response:**
```json
{
  "token": "jwt-token-here",
  "device_id": "android-device-uuid",
  "device_metadata": {
    "model": "Pixel 7",
    "android_version": "14",
    "forge_os_version": "1.0.0",
    "capabilities": ["sync", "clipboard", "notifications"]
  }
}
```

#### GET /api/tool/{opId}/status
Query the status of a tool operation.

**Response:**
```json
{
  "opId": "uuid",
  "toolName": "file_read",
  "status": "running",
  "progress": {
    "percent": 45,
    "message": "Reading file..."
  },
  "startTime": 1703001000
}
```

#### POST /api/tool/{opId}/cancel
Cancel an in-progress tool operation.

**Response:**
```json
{
  "opId": "uuid",
  "cancelled": true
}
```

#### POST /api/sync/upload
Upload a file chunk to the device.

**Request (multipart/form-data):**
```
path: workspace/relative/file.txt
chunk: 0
totalChunks: 5
checksum: sha256-hash
data: <binary>
```

**Response:**
```json
{
  "uploaded": true,
  "receivedChunks": [0, 1, 2],
  "complete": false
}
```

#### GET /api/sync/download
Download a file from the device.

**Query Parameters:**
- `path`: Workspace-relative file path
- `chunk`: Optional chunk index for resumable downloads

**Response:** Binary file data with Content-Range header

#### POST /api/clipboard
Update the device clipboard.

**Request:**
```json
{
  "type": "text",
  "content": "Hello from desktop"
}
```

**Response:**
```json
{
  "updated": true
}
```

#### GET /api/config
Retrieve device configuration.

**Response:**
```json
{
  "theme": "dark",
  "sync_enabled": true,
  "clipboard_enabled": true,
  "notification_filters": ["com.example.app"]
}
```

#### POST /api/config
Update device configuration.

**Request:**
```json
{
  "theme": "light",
  "sync_enabled": false
}
```

**Response:**
```json
{
  "updated": true
}
```

### 4.2 WebSocket Protocol

#### Connection
```
ws://device-ip:8789/api/events
Authorization: Bearer <token>
```

#### Authentication Message (Client → Server)
```json
{
  "type": "auth",
  "token": "connection-token"
}
```

#### Subscription Message (Client → Server)
```json
{
  "type": "subscribe",
  "events": ["tool_start", "tool_complete", "notification"]
}
```

#### Event Messages (Server → Client)
```json
{
  "type": "tool_complete",
  "timestamp": 1703001000,
  "payload": {
    "opId": "uuid",
    "output": "File contents...",
    "duration": 150
  }
}
```

#### Desktop Tool Invocation (Server → Client)
```json
{
  "type": "desktop_tool_invoke",
  "timestamp": 1703001000,
  "payload": {
    "invokeId": "uuid",
    "toolName": "browser_open",
    "args": { "url": "https://example.com" },
    "timeout": 30
  }
}
```

#### Desktop Tool Result (Client → Server)
```json
{
  "type": "desktop_tool_result",
  "invokeId": "uuid",
  "success": true,
  "output": "Browser opened"
}
```

## 5. Security Architecture

### 5.1 Authentication Flow

```
Desktop                           Android
   |                                 |
   |-- POST /api/pairing/initiate -->|
   |<-- { pairing_code: "123456" } --|
   |                                 |
   |         (User enters code)       |
   |                                 |
   |-- POST /api/pairing/confirm ---->|
   |    { pairing_code: "123456" }   |
   |<-- { token: "jwt-token" } -------|
   |                                 |
   |   (Store encrypted token)       |
   |                                 |
   |-- All requests with token ----->|
   |   Authorization: Bearer <token> |
```

### 5.2 Token Format

JWT with HS256 signing:
```json
{
  "iss": "forge-os",
  "sub": "desktop-client-id",
  "iat": 1703001000,
  "exp": 1734537000,
  "device_id": "android-device-uuid",
  "permissions": ["tools", "sync", "clipboard", "notifications"]
}
```

### 5.3 Encryption

- **Connection Tokens**: Encrypted with OS keychain (macOS Keychain, Windows Credential Store, Linux Secret Service)
- **Clipboard Data**: AES-256-GCM with ephemeral keys
- **File Transfers**: TLS 1.3 (future enhancement)

### 5.4 Permission Model

Desktop clients request specific permissions during pairing:
- `tools`: Execute tools
- `sync`: File synchronization
- `clipboard`: Clipboard access
- `notifications`: Notification bridging
- `config`: Configuration management

## 6. Connection Management

### 6.1 Discovery Protocol

**mDNS Service Advertisement (Android):**
```
Service: _forgeos._tcp.local
Port: 8789
TXT Records:
  - id=<device-uuid>
  - version=1.0.0
  - model=Pixel 7
  - capabilities=tools,sync,clipboard,notifications
```

**Discovery Sequence:**
```
Desktop                            Network
   |                                  |
   |-- mDNS Query (_forgeos._tcp) --->|
   |<-- Service Responses ------------|
   |   [Device A, Device B, ...]      |
   |                                  |
   |-- GET /api/status (validation)-->|
   |<-- { status: "ok", ... } --------|
```

### 6.2 Connection Fallback

```rust
async fn connect_to_device(profile: &ConnectionProfile) -> Result<Connection> {
    // Try methods in order of preference
    let methods = vec![
        ConnectionMethod::Tcp,
        ConnectionMethod::Adb,
        ConnectionMethod::Relay,
    ];
    
    for method in methods {
        match try_connect(profile, method).await {
            Ok(conn) => return Ok(conn),
            Err(e) => log::warn!("Failed {}: {}", method, e),
        }
    }
    
    Err(Error::AllConnectionMethodsFailed)
}
```

### 6.3 Health Monitoring

```typescript
class HealthMonitor {
  private failureCount = 0;
  private latencyHistory: number[] = [];
  
  async checkHealth() {
    const start = Date.now();
    try {
      await checkStatus(this.config);
      const latency = Date.now() - start;
      this.latencyHistory.push(latency);
      this.failureCount = 0;
      
      if (latency > 1000) {
        this.emit('high-latency', latency);
      }
      
      this.emit('connected', { latency });
    } catch (error) {
      this.failureCount++;
      if (this.failureCount >= 3) {
        this.emit('disconnected');
      }
    }
  }
}
```

## 7. File Synchronization

### 7.1 Sync Algorithm

```
1. Desktop monitors sync folder with file watcher
2. On file change:
   a. Compute SHA-256 checksum
   b. Split file into 1MB chunks
   c. Upload chunks sequentially
   d. Device reassembles and verifies checksum
   e. Device emits file_modified event
3. Desktop receives file_modified event
4. If file doesn't exist locally:
   a. Request download
   b. Receive chunks
   c. Verify checksum
   d. Write to disk
```

### 7.2 Conflict Resolution

```kotlin
fun resolveConflict(local: FileEntry, remote: FileEntry): Resolution {
    return when {
        local.lastModified > remote.lastModified -> {
            // Keep local, upload to device
            Resolution.UseLocal(local)
        }
        local.lastModified < remote.lastModified -> {
            // Keep remote, download from device
            Resolution.UseRemote(remote)
        }
        else -> {
            // Same timestamp, different content - keep both
            val conflictName = "${local.name}.conflict-${System.currentTimeMillis()}"
            Resolution.KeepBoth(local, remote, conflictName)
        }
    }
}
```

## 8. Performance Considerations

### 8.1 Optimization Strategies

1. **Event Batching**: Batch multiple events within 100ms window to reduce WebSocket messages
2. **Tool Schema Caching**: Cache tool definitions for 5 minutes to reduce API calls
3. **Lazy Connection**: Only establish WebSocket when event subscriptions exist
4. **Chunked Transfers**: 1MB chunks for file uploads/downloads with resumption
5. **Compression**: Gzip compression for clipboard content > 1KB

### 8.2 Resource Limits

- Max concurrent WebSocket connections: 5 per token
- Max tool operation timeout: 10 minutes
- Max clipboard size: 5MB
- Max file sync size: 100MB per file
- Event buffer size: 1000 events

### 8.3 Network Efficiency

```typescript
// Exponential backoff for reconnection
const getReconnectDelay = (attempt: number) => {
  return Math.min(1000 * Math.pow(2, attempt), 30000); // Max 30s
};

// Adaptive health check interval based on latency
const getHealthCheckInterval = (latency: number) => {
  if (latency < 100) return 10000;  // 10s
  if (latency < 500) return 15000;  // 15s
  return 20000;  // 20s for high latency
};
```

## 9. Error Handling

### 9.1 Error Categories

```typescript
enum ErrorCategory {
  NETWORK = 'network',           // TCP/WebSocket failures
  AUTHENTICATION = 'auth',       // 401, invalid tokens
  VALIDATION = 'validation',     // Parameter validation
  TIMEOUT = 'timeout',           // Operation timeouts
  PERMISSION = 'permission',     // Missing permissions
  DEVICE = 'device',            // Device-side errors
  UNKNOWN = 'unknown'
}

interface ForgeError {
  category: ErrorCategory;
  code: string;
  message: string;
  retryable: boolean;
  context?: Record<string, unknown>;
}
```

### 9.2 Circuit Breaker

```rust
struct CircuitBreaker {
    state: CircuitState,
    failure_count: usize,
    failure_threshold: usize,
    timeout: Duration,
    last_failure: Option<Instant>,
}

enum CircuitState {
    Closed,      // Normal operation
    Open,        // Blocking requests
    HalfOpen,    // Testing recovery
}

impl CircuitBreaker {
    fn should_allow_request(&mut self) -> bool {
        match self.state {
            CircuitState::Closed => true,
            CircuitState::Open => {
                if self.last_failure.unwrap().elapsed() > self.timeout {
                    self.state = CircuitState::HalfOpen;
                    true
                } else {
                    false
                }
            }
            CircuitState::HalfOpen => true,
        }
    }
    
    fn record_success(&mut self) {
        self.failure_count = 0;
        self.state = CircuitState::Closed;
    }
    
    fn record_failure(&mut self) {
        self.failure_count += 1;
        self.last_failure = Some(Instant::now());
        
        if self.failure_count >= self.failure_threshold {
            self.state = CircuitState::Open;
        }
    }
}
```

## 10. Testing Strategy

### 10.1 Unit Tests

- ConnectionManager: Profile CRUD, connection lifecycle
- ToolValidator: Schema validation, type checking
- SyncEngine: Conflict resolution, checksum verification
- EventStreamClient: Reconnection logic, message parsing

### 10.2 Integration Tests

- End-to-end pairing flow
- Tool execution with progress tracking
- File upload/download with chunking
- WebSocket reconnection scenarios
- Multi-client connection handling

### 10.3 Mock Server Extensions

Enhance `mock_server.py` to support:
- WebSocket endpoint simulation
- Pairing flow
- Async tool operations
- Event broadcasting
- File upload/download

## 11. Implementation Phases

### Phase 1: Core Infrastructure (Weeks 1-2)
- Extend ForgeHttpServer with new endpoints
- Implement WebSocket handler on Android
- Build ConnectionManager and ProfileManager on desktop
- Add Discovery Service (mDNS)

### Phase 2: Enhanced Tool Execution (Week 3)
- Async tool operations with opId
- Progress tracking and cancellation
- Tool schema validation on desktop
- Desktop Tool Registry

### Phase 3: Real-Time Communication (Week 4)
- EventStreamClient implementation
- Event broadcasting on Android
- Reconnection logic with exponential backoff

### Phase 4: File Synchronization (Week 5)
- File watcher on desktop
- Chunked upload/download
- Conflict resolution
- Checksum verification

### Phase 5: Clipboard & Notifications (Week 6)
- Clipboard monitoring on both sides
- Clipboard sync via WebSocket
- NotificationListenerService on Android
- Native notification display on desktop

### Phase 6: Polish & Monitoring (Week 7)
- Health monitoring dashboard
- Performance metrics
- Logging and diagnostics
- Offline operation queue
- Configuration sync

## 12. Migration Strategy

### 12.1 Backward Compatibility

- Maintain existing HTTP endpoints
- Version API with `/api/v1/` prefix for new endpoints
- Feature detection via `/api/status` capabilities field
- Graceful degradation for older clients

### 12.2 Data Migration

```typescript
// Migrate old ConnectionConfig to new ConnectionProfile
function migrateConnection(old: ConnectionConfig): ConnectionProfile {
  return {
    id: generateUUID(),
    name: `Device ${old.host}`,
    deviceId: '', // Fetch on first connection
    host: old.host,
    port: old.port,
    token: old.token,
    connectionMethod: 'tcp',
    lastConnected: Date.now(),
    deviceMetadata: {
      model: 'Unknown',
      androidVersion: 'Unknown',
      forgeOsVersion: 'Unknown',
      capabilities: [],
    },
  };
}
```

## 13. Deployment Considerations

### 13.1 Desktop Distribution

- Tauri auto-updater for seamless updates
- Platform-specific installers (DMG, MSI, AppImage)
- Code signing for security

### 13.2 Android Updates

- Incremental rollout via Google Play
- Feature flags for gradual enablement
- Crash reporting with Firebase Crashlytics

### 13.3 Monitoring

- Desktop: Sentry for error tracking
- Android: Firebase Analytics for usage metrics
- Connection success rates and latency metrics

## 14. Open Questions

1. **Relay Server Implementation**: What protocol for NAT traversal? (TURN/WebRTC?)
2. **File Sync Scope**: Should we support bi-directional sync or just desktop → device?
3. **Desktop Tool Security**: Should all desktop tools require confirmation dialogs?
4. **Token Rotation**: Automatic rotation schedule or manual only?
5. **Multi-Device Sync**: Should config sync across multiple desktop clients?

## 15. Future Enhancements

- End-to-end encryption for all data transfers
- Voice/video call bridging
- SMS/call handling from desktop
- Camera access from desktop
- Screen mirroring
- Gesture control from desktop
