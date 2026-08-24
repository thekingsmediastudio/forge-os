# Implementation Plan: Forge Desktop Integration

## Overview

This implementation plan establishes bidirectional integration between Forge OS (Android) and Forge Desktop (Tauri application), extending the basic HTTP API with WebSocket event streaming, connection management, file synchronization, clipboard sharing, and notification bridging.

**Implementation Languages:**
- **Android**: Kotlin
- **Desktop Frontend**: TypeScript (React)
- **Desktop Backend**: Rust (Tauri)

## Tasks

- [x] 1. Set up Android HTTP API extensions in ForgeHttpServer
  - [x] 1.1 Add pairing endpoints in Kotlin
    - Create POST /api/pairing/initiate endpoint that generates 6-digit pairing codes with 5-minute expiration
    - Create POST /api/pairing/confirm endpoint that validates code and issues JWT token
    - Use HS256 signing for JWT with device_id, desktop_id, and permissions claims
    - Store active pairing codes in memory with timestamp expiration
    - _Requirements: 2.3, 2.4, 2.5, 2.6_
  
  - [x] 1.2 Add async tool operation endpoints in Kotlin
    - Modify POST /api/tool to return operation ID (UUID) immediately
    - Create GET /api/tool/{opId}/status endpoint returning operation state and progress
    - Create POST /api/tool/{opId}/cancel endpoint for cancellation
    - _Requirements: 4.1, 4.2, 4.3, 4.6_
  
  - [x] 1.3 Add file sync endpoints in Kotlin
    - Create POST /api/sync/upload accepting multipart/form-data with path, chunk index, totalChunks, checksum, and data fields
    - Create GET /api/sync/download with path query parameter and optional chunk index
    - Support resumable uploads by tracking received chunks per file
    - Implement Content-Range header for chunked downloads
    - _Requirements: 5.1, 5.2, 5.6_
  
  - [x] 1.4 Add clipboard endpoint in Kotlin
    - Create POST /api/clipboard endpoint accepting JSON with type (text/image/file) and content
    - Update Android ClipboardManager with received content
    - Support text up to 1MB and images in PNG/JPEG format
    - _Requirements: 6.1, 6.2, 6.3, 6.4_
  
  - [x] 1.5 Add config endpoints in Kotlin
    - Create GET /api/config returning JSON configuration object
    - Create POST /api/config accepting JSON configuration updates
    - Validate configuration schema against predefined Kotlin data class
    - Store configuration in SharedPreferences
    - _Requirements: 20.1, 20.2, 20.7_

- [x] 2. Implement WebSocket event streaming on Android in Kotlin
  - [x] 2.1 Create WebSocket handler at /api/events in ForgeHttpServer
    - Implement WebSocket upgrade handler in Ktor
    - Authenticate connections using Bearer token from Authorization header or query parameter
    - Parse subscription messages from clients specifying event type filters
    - Track connected clients with their subscription preferences
    - _Requirements: 8.1, 8.2, 8.7_
  
  - [x] 2.2 Implement EventBroadcaster service in Kotlin
    - Create event queue with ConcurrentLinkedQueue and 1000 event buffer limit
    - Define EventMessage data class with type, timestamp, and payload fields
    - Support event types: tool_start, tool_progress, tool_complete, tool_error, agent_turn, file_modified, notification, clipboard, config_changed, desktop_tool_invoke
    - Broadcast events to all connected WebSocket clients matching subscription filters within 500ms
    - Use Kotlin coroutines for asynchronous broadcasting
    - _Requirements: 8.3, 8.4, 8.5_
  
  - [x] 2.3 Integrate EventBroadcaster with ToolExecutionManager
    - Emit tool_start event with opId, toolName, and args when tool execution begins
    - Emit tool_progress events during execution with percent and message
    - Emit tool_complete event with output, duration, and resourceUsage on success
    - Emit tool_error event with error details on failure
    - _Requirements: 4.4, 4.8_
  
  - [x] 2.4 Implement connection limit enforcement in Kotlin
    - Track concurrent WebSocket connections per token in ConcurrentHashMap
    - Reject new connections when limit (5 per token) is reached with WebSocket close frame
    - Remove connections from tracking on disconnect
    - Support up to 10 total concurrent connections across all tokens
    - _Requirements: 8.8, 17.4, 17.5_

- [x] 3. Build Desktop ConnectionManager and Discovery in TypeScript and Rust
  - [x] 3.1 Implement ConnectionManager in TypeScript
    - Create ConnectionProfile interface with id, name, deviceId, host, port, encrypted token, connectionMethod, lastConnected, and deviceMetadata fields
    - Implement connect(), disconnect(), and reconnect() methods
    - Store profiles in localStorage with AES-256-GCM encrypted tokens
    - Implement getProfiles(), saveProfile(), and deleteProfile() methods
    - _Requirements: 1.4, 10.1, 10.2, 10.3, 10.7_
  
  - [x] 3.2 Implement DiscoveryService in Rust Tauri backend
    - Use mdns crate for mDNS service discovery on _forgeos._tcp.local
    - Parse TXT records from mDNS responses to extract device metadata (id, version, model, capabilities)
    - Implement ADB device enumeration by executing `adb devices` command and parsing output
    - Expose Tauri commands discover_devices() and list_adb_devices()
    - _Requirements: 1.1, 1.2, 13.7_
  
  - [x] 3.3 Implement connection fallback strategy in TypeScript
    - Attempt TCP connection first using fetch() to http://{host}:{port}/api/status
    - If TCP fails, attempt ADB tunnel using Tauri command create_adb_tunnel()
    - If ADB fails, attempt relay server connection (placeholder for future implementation)
    - Log each attempt and display active connection method in UI
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6_
  
  - [x] 3.4 Implement automatic reconnection in TypeScript
    - Monitor network changes using navigator.onLine and window addEventListener('online')
    - Trigger reconnection attempts within 5 seconds of network availability
    - Mark devices as offline after 30 seconds of unreachability
    - Trigger rescan on network change events
    - _Requirements: 1.5, 1.6, 1.7_

- [x] 4. Implement secure storage and pairing UI in Rust and TypeScript
  - [x] 4.1 Integrate OS keychain in Rust Tauri backend
    - Use keyring crate for cross-platform secure storage
    - Implement store_token(profile_id, token) Tauri command using Keychain (macOS), Credential Store (Windows), Secret Service (Linux)
    - Implement get_token(profile_id) Tauri command to retrieve encrypted tokens
    - Implement delete_token(profile_id) Tauri command
    - _Requirements: 10.7, 2.1_
  
  - [ ] 4.2 Build pairing flow UI in React TypeScript
    - Create PairingScreen component with device selection and initiate button
    - Display 6-digit confirmation code input field with auto-focus
    - Show loading state during pairing process
    - Display success notification with device metadata on successful pairing
    - Display error message on pairing failure with retry option
    - _Requirements: 2.3, 2.4, 2.5_
  
  - [x] 4.3 Implement token rotation support in TypeScript
    - Detect HTTP 401 responses as token expiration signal
    - Prompt user for re-authentication without requiring full re-pairing
    - Update stored token in keychain after successful rotation
    - Retry failed request with new token
    - _Requirements: 2.7_

- [x] 5. Build HealthMonitor and connection status UI in TypeScript
  - [x] 5.1 Implement HealthMonitor class in TypeScript
    - Poll GET /api/status every 10 seconds using setInterval
    - Track round-trip latency using Date.now() before and after request
    - Maintain rolling latency history array (last 10 measurements)
    - Implement 3-failure threshold counter for marking connection as disconnected
    - Emit 'connected', 'disconnected', 'high-latency' events using EventEmitter
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 5.2 Implement adaptive health check intervals in TypeScript
    - Calculate average latency from rolling history
    - Adjust polling interval: 10s for latency <100ms, 15s for 100-500ms, 20s for >500ms
    - Clear and restart setInterval when interval changes
    - _Requirements: 3.5_
  
  - [x] 5.3 Build connection status UI component in React TypeScript
    - Display connection state badge (Connected/Disconnected/Connecting) with color coding
    - Show current latency in milliseconds with moving average
    - Display last successful connection timestamp formatted as relative time
    - Show warning indicator when latency exceeds 1000ms
    - Display count of other connected clients from /api/status response
    - _Requirements: 3.2, 3.5, 3.6, 3.7, 17.6_

- [x] 6. Implement EventStreamClient and reconnection logic in TypeScript
  - [ ] 6.1 Build EventStreamClient class in TypeScript
    - Establish WebSocket connection to ws://{host}:{port}/api/events with Authorization header containing Bearer token
    - Send subscription message as JSON with event type filter array after connection established
    - Parse incoming WebSocket messages as JSON EventMessage objects
    - Emit parsed events to application using EventEmitter pattern
    - Store current subscription filters for resubscription after reconnect
    - _Requirements: 8.2, 8.7_
  
  - [ ] 6.2 Implement reconnection with exponential backoff in TypeScript
    - Track reconnection attempt counter starting at 0
    - Calculate backoff delay: Math.min(1000 * Math.pow(2, attempt), 30000) with max 30 seconds
    - Retry connection on WebSocket close or error events
    - Preserve subscription filters across reconnections and resubscribe after successful reconnect
    - Emit 'reconnected' notification event to UI
    - Reset attempt counter to 0 on successful connection
    - _Requirements: 8.6, 3.7, 14.1_

- [x] 7. Implement ToolValidator with JSON Schema in TypeScript
  - [ ] 7.1 Fetch and cache tool definitions in TypeScript
    - Call GET /api/tools on initial connection and store in memory cache
    - Refresh definitions every 5 minutes using setInterval
    - Parse response as array of tool definitions with name, description, and parameters schema
    - _Requirements: 11.1, 11.7_
  
  - [ ] 7.2 Implement parameter validation using Zod in TypeScript
    - Convert JSON Schema from tool definitions to Zod schemas
    - Validate required parameters are present before tool invocation
    - Validate parameter types match expected types (string, number, boolean, object, array)
    - Validate enum constraints if defined in schema
    - Throw validation errors with detailed messages including parameter name, expected type, and actual value
    - _Requirements: 11.3, 11.4, 11.5, 11.6_
  
  - [ ] 7.3 Generate TypeScript type definitions from schemas
    - Use json-schema-to-typescript library to generate interface definitions for each tool
    - Export generated types for compile-time type checking in tool invocations
    - Regenerate types when tool definitions are refreshed
    - _Requirements: 11.2_

- [x] 8. Implement async tool operations and cancellation in Kotlin
  - [x] 8.1 Update ToolExecutionManager to track operations
    - Generate unique operation IDs using UUID.randomUUID()
    - Store operation status in ConcurrentHashMap<String, ToolOperation>
    - Modify POST /api/tool handler to return opId immediately after starting async execution
    - Create ToolOperation data class with opId, toolName, args, status, startTime, endTime, progress, result, error fields
    - _Requirements: 4.2, 4.3_
  
  - [x] 8.2 Implement progress tracking in Kotlin
    - Add reportProgress(opId, percent, message) method to ToolExecutionManager
    - Allow tools to call reportProgress() during execution
    - Update ToolOperation progress field in ConcurrentHashMap
    - Emit tool_progress event via EventBroadcaster with opId, percent, and message
    - _Requirements: 4.4_
  
  - [x] 8.3 Implement cancellation support in Kotlin
    - Create POST /api/tool/{opId}/cancel endpoint handler
    - Store coroutine Job reference in ToolOperation
    - Call job.cancel() on cancellation request
    - Set operation status to 'cancelled'
    - Emit tool_error event with cancellation reason within 5 seconds
    - _Requirements: 4.5, 4.6_
  
  - [x] 8.4 Add execution metadata tracking in Kotlin
    - Track startTime using System.currentTimeMillis() when operation begins
    - Calculate duration as endTime - startTime when operation completes
    - Track CPU time and memory usage using ThreadMXBean and Runtime APIs
    - Include metadata in tool_complete events with cpuMs and memoryBytes fields
    - _Requirements: 4.7, 4.8_

- [x] 9. Implement file synchronization in Rust and Kotlin
  - [ ] 9.1 Build file watcher in Rust Tauri backend
    - Use notify crate to monitor sync directories for file changes (create, modify, delete events)
    - Implement 500ms debounce timer using tokio::time::sleep to batch rapid changes
    - Expose Tauri command start_file_sync(directory_path) to begin monitoring
    - _Requirements: 5.1, 5.2_
  
  - [ ] 9.2 Implement SyncEngine upload logic in Rust
    - Compute SHA-256 checksum using sha2 crate for changed files
    - Split files into 1MB chunks using std::io::Read with fixed buffer
    - Upload chunks via POST /api/sync/upload with multipart/form-data using reqwest
    - Include path, chunk index, totalChunks, and checksum in each request
    - Track upload progress and retry failed chunks up to 3 times
    - _Requirements: 5.2, 5.6, 5.7_
  
  - [x] 9.3 Implement SyncService on Android in Kotlin
    - Receive multipart file chunks in POST /api/sync/upload handler
    - Store chunks temporarily using File.createTempFile()
    - Reassemble complete file when all chunks received
    - Verify SHA-256 checksum matches expected value
    - Write assembled file to workspace directory
    - Emit file_modified event via EventBroadcaster with file path
    - _Requirements: 5.2, 5.7_
  
  - [x] 9.4 Implement download handling in Rust
    - Request files via GET /api/sync/download?path={workspace_relative_path}
    - Support resumable downloads by sending Range header with byte range
    - Parse Content-Range response header for partial content
    - Verify SHA-256 checksum after download completes
    - Write file to local sync directory
    - _Requirements: 5.3, 5.6, 5.7_
  
  - [ ] 9.5 Implement conflict resolution in Rust and Kotlin
    - Detect conflicts by comparing checksums and lastModified timestamps from both sides
    - Use last-write-wins strategy: keep file with most recent lastModified timestamp
    - When checksums differ but timestamps are equal, preserve both versions
    - Rename conflict file with .conflict-{timestamp} suffix
    - Log conflict resolution decision
    - _Requirements: 5.4, 5.5_
  
  - [ ] 9.6 Add compression for bandwidth-limited mode in Rust
    - Check if bandwidth-limited mode is enabled in configuration
    - Compress files using flate2 crate with gzip compression before chunking and transfer
    - Add compressed flag to upload request metadata
    - Decompress on Android receiving end using java.util.zip.GZIPInputStream
    - _Requirements: 5.8_

- [x] 10. Implement clipboard synchronization in Rust and Kotlin
  - [ ] 10.1 Build ClipboardManager in Rust Tauri backend
    - Monitor system clipboard using platform-specific APIs: NSPasteboard (macOS), Windows Clipboard API, X11/Wayland clipboard (Linux)
    - Use arboard crate for cross-platform clipboard access
    - Debounce clipboard changes with 500ms timer using tokio::time::sleep
    - Encrypt clipboard data with AES-256-GCM using aes-gcm crate with randomly generated nonce
    - _Requirements: 6.1, 6.7_
  
  - [ ] 10.2 Send clipboard updates via WebSocket in Rust
    - Send clipboard event messages to Android via WebSocket with type (text/image/file) and encrypted content
    - Support text content up to 1MB with base64 encoding for transmission
    - Support PNG and JPEG image formats with image crate for format detection
    - For content >5MB, upload via file transfer endpoint instead of WebSocket
    - _Requirements: 6.1, 6.3, 6.4, 6.5_
  
  - [ ] 10.3 Implement ClipboardService on Android in Kotlin
    - Listen for clipboard events from WebSocket EventStream
    - Decrypt received clipboard data using AES-256-GCM with provided nonce
    - Update Android ClipboardManager using clipboardManager.setPrimaryClip()
    - Monitor Android clipboard for changes using ClipboardManager.OnPrimaryClipChangedListener
    - Encrypt and send Android clipboard updates to EventBroadcaster as clipboard events
    - _Requirements: 6.2, 6.6_

- [x] 11. Implement notification bridging in Kotlin and Rust
  - [ ] 11.1 Create NotificationListenerService on Android in Kotlin
    - Extend NotificationListenerService base class with proper lifecycle methods
    - Override onNotificationPosted() to capture StatusBarNotification events
    - Extract notification metadata: getNotification().extras for title and body, getPackageName() for app
    - Convert notification icon to Base64-encoded PNG using Bitmap and Base64.encodeToString()
    - Extract action buttons from notification.actions array
    - Apply filter rules from SharedPreferences configuration to skip unwanted notifications
    - _Requirements: 7.1, 7.3, 7.6_
  
  - [ ] 11.2 Send notifications via EventBroadcaster in Kotlin
    - Emit notification events with id, packageName, title, body, icon (Base64), and actions array
    - Include timestamp in event payload
    - Broadcast to all connected desktop clients within 3 seconds
    - _Requirements: 7.1, 7.3_
  
  - [ ] 11.3 Display notifications on Desktop in Rust and TypeScript
    - Listen for notification events in EventStreamClient TypeScript
    - Use Tauri notification API to show native desktop notifications
    - Display title, body, and icon (decode Base64 and create temporary icon file)
    - Group notifications by packageName in UI component
    - Use platform-specific notification systems: Notification Center (macOS), Action Center (Windows), libnotify (Linux)
    - _Requirements: 7.2, 7.7_
  
  - [ ] 11.4 Handle notification actions in TypeScript and Kotlin
    - Capture notification action button clicks in desktop notification handler
    - Send action click event back to Android via WebSocket with notification id and action id
    - Trigger corresponding PendingIntent action on Android using notification.actions[actionIndex].actionIntent.send()
    - Synchronize notification dismissals: listen for onNotificationRemoved() on Android and send dismissal event
    - Dismiss desktop notification when Android notification is removed
    - _Requirements: 7.4, 7.5_

- [x] 12. Implement Desktop Tool Registry in Rust and Kotlin
  - [ ] 12.1 Create DesktopToolRegistry in Rust Tauri backend
    - Provide Tauri command register_desktop_tool(name, description, parameters_schema) for tool registration
    - Generate JSON Schema tool definitions from parameters
    - Store registered tools in HashMap<String, DesktopTool> with tool metadata
    - Send tool definitions to Android via WebSocket message with type desktop_tool_register
    - _Requirements: 15.1, 15.2, 15.3_
  
  - [ ] 12.2 Handle desktop tool invocations in Rust
    - Listen for desktop_tool_invoke events from WebSocket in EventStreamClient
    - Look up tool by name in registry HashMap
    - Execute tool function with provided args and timeout using tokio::time::timeout
    - Support async tools with progress callbacks using tokio channels
    - Return results via WebSocket message with type desktop_tool_result including invokeId, success, and output
    - Handle execution errors and return error details in result message
    - _Requirements: 15.4, 15.5, 15.6_
  
  - [ ] 12.3 Implement confirmation dialogs in Rust
    - Check if tool requires confirmation from tool metadata requiresConfirmation flag
    - Display native confirmation dialog using tauri::api::dialog::message before execution
    - Block tool execution until user approves or rejects
    - Return error if user rejects confirmation
    - _Requirements: 15.7_

- [x] 13. Implement PairingService on Android in Kotlin
  - [x] 13.1 Create PairingService class in Kotlin
    - Generate random 6-digit pairing codes using Random.nextInt(100000, 999999)
    - Store active pairing codes in ConcurrentHashMap<String, PairingRequest> with timestamp
    - Implement 5-minute expiration check by comparing current time with stored timestamp
    - Remove expired codes from map automatically using scheduled coroutine
    - _Requirements: 2.4_
  
  - [ ] 13.2 Implement JWT token generation in Kotlin
    - Use java-jwt library (com.auth0:java-jwt) for JWT creation
    - Sign tokens with HS256 algorithm using secret key from SecureKeyStore
    - Include claims: desktop_id (from request), device_id (Android device UUID), permissions array, issued_at, expires_at (1 year)
    - Set issuer as "forge-os" and subject as desktop_id
    - _Requirements: 2.6_
    - _Note: JWT implemented (java-jwt HS256, claims iss=forge-os / sub / device_id / permissions / exp 1y)_
  
  - [ ] 13.3 Store tokens in SecureKeyStore in Kotlin
    - Encrypt tokens using Android Keystore with AES-256-GCM before storage
    - Associate tokens with desktop client IDs as key in SharedPreferences
    - Implement getToken(desktop_id), saveToken(desktop_id, token), and revokeToken(desktop_id) methods
    - _Requirements: 2.1_
    - _Note: Stored via PairingService.saveToken/getToken/revokeToken -> SecureKeyStore custom keys_

- [x] 14. Build performance monitoring and metrics in TypeScript
  - [ ] 14.1 Create MetricsCollector class in TypeScript
    - Record execution start and end time for each tool invocation using Date.now()
    - Calculate execution duration as endTime - startTime
    - Track success/failure counts per tool in Map<string, {success: number, failure: number}>
    - Calculate average, min, max execution times per tool using array reduce
    - Store metrics in memory with Map<string, ToolMetrics>
    - _Requirements: 18.1, 18.2, 18.3_
  
  - [ ] 14.2 Build monitoring dashboard UI in React TypeScript
    - Create MonitoringDashboard component displaying table of tool names with metrics
    - Show success rate as percentage: (success / (success + failure)) * 100
    - Highlight tools with execution time >200% of average in warning color
    - Display average, min, max execution times in milliseconds for each tool
    - Update metrics in real-time as tool executions complete
    - _Requirements: 18.4, 18.5_
  
  - [ ] 14.3 Implement metrics export in TypeScript
    - Export metrics to CSV format with columns: toolName, avgTime, minTime, maxTime, successCount, failureCount, successRate
    - Use papaparse library for CSV generation
    - Provide download button triggering browser download with Blob and URL.createObjectURL
    - Implement reset button clearing all metrics from memory
    - _Requirements: 18.6, 18.7_
  
  - [ ] 14.4 Implement data usage tracking in TypeScript
    - Track bytes sent/received for each HTTP request and WebSocket message using Content-Length header and message.length
    - Maintain per-session cumulative counters for total bytes
    - Track per-feature usage: file sync, clipboard, notifications, tool calls in separate counters
    - Display data usage in UI with human-readable format (KB, MB, GB)
    - Show per-feature breakdown in pie chart or bar chart
    - Emit warning notification when usage exceeds configured threshold from settings
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [x] 15. Implement logging and diagnostics in TypeScript and Rust
  - [ ] 15.1 Add comprehensive logging to Desktop in TypeScript and Rust
    - Log all API requests with timestamp (ISO 8601), endpoint, HTTP method, and parameters
    - Log all API responses with status code, response body (truncated if >1KB), and duration in milliseconds
    - Log WebSocket messages in debug mode including message type and payload
    - Redact Connection_Tokens from logs using regex replacement with "***REDACTED***"
    - Use winston logger library for TypeScript with file and console transports
    - Use log and env_logger crates for Rust logging
    - _Requirements: 16.1, 16.2, 16.3, 16.6_
  
  - [ ] 15.2 Implement log rotation in TypeScript and Rust
    - Rotate log files when size exceeds 10MB using winston-daily-rotate-file transport
    - Keep last 5 log files and delete older ones automatically
    - Name log files with timestamp: forge-desktop-{date}.log
    - _Requirements: 16.4_
  
  - [ ] 15.3 Add diagnostic export in TypeScript
    - Create export_diagnostics() function collecting last 1000 log entries
    - Include system information: OS, version, architecture from Tauri system API
    - Include connection profiles (with tokens redacted)
    - Include last 10 error reports with full stack traces and request/response context
    - Export as JSON file with timestamp in filename
    - Provide download button in UI
    - _Requirements: 16.5, 16.7_

- [x] 16. Implement error recovery and offline queue in TypeScript and Rust
  - [ ] 16.1 Add retry logic with exponential backoff in TypeScript
    - Wrap all HTTP requests in retry wrapper function
    - Retry network errors (connection refused, timeout) up to 3 times
    - Calculate backoff delay: Math.min(1000 * Math.pow(2, attempt), 10000)
    - Only retry idempotent operations (GET, PUT with idempotency token, POST with retry-safe flag)
    - Log each retry attempt with reason and delay
    - _Requirements: 14.1, 14.2_
  
  - [ ] 16.2 Implement circuit breaker in Rust
    - Create CircuitBreaker struct with state (Closed/Open/HalfOpen), failure_count, failure_threshold (5), timeout (30s)
    - Track consecutive failures per connection profile
    - Open circuit after threshold failures blocking all requests
    - Transition to HalfOpen state after timeout allowing test requests
    - Close circuit on successful request resetting failure count
    - _Requirements: 14.6, 14.7_
  
  - [ ] 16.3 Build offline operation queue in TypeScript
    - Create OfflineQueue class storing operations in IndexedDB for persistence
    - Add operations to queue when connection is disconnected
    - Process queued operations in FIFO order when connection restored using Array.shift()
    - Skip operations older than 1 hour by comparing timestamp
    - Retry failed operations according to retry policy from task 16.1
    - Display pending queue size in connection status UI
    - Provide user button to cancel all queued operations
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7_
  
  - [ ] 16.4 Preserve pending operations across reconnections in TypeScript
    - Store in-progress operation state in memory Map<string, OperationState>
    - On connection restored event, check for pending operations
    - Resume operations using stored state and opId
    - Display error notification with retry button after 3 failures
    - Allow user to manually retry failed operations from notification
    - _Requirements: 14.3, 14.4, 14.5_

- [x] 17. Implement configuration synchronization in TypeScript and Kotlin
  - [ ] 17.1 Sync config changes from Desktop to Android in TypeScript
    - Send config updates via POST /api/config with JSON body when user modifies settings
    - Validate configuration object against JSON Schema before sending
    - Handle validation errors by displaying error message to user
    - _Requirements: 20.1, 20.7_
  
  - [ ] 17.2 Sync config changes from Android to Desktop in TypeScript
    - Listen for config_changed events from EventStream WebSocket
    - Merge incoming config with local settings using Object.assign()
    - Use server-wins resolution strategy for conflicts by preferring server values
    - Update UI to reflect new configuration values
    - _Requirements: 20.2, 20.3, 20.4_
  
  - [ ] 17.3 Support per-device configuration profiles in TypeScript
    - Store separate configuration objects per device profile ID in localStorage
    - Load appropriate config when switching between device profiles
    - Define desktop-specific settings list (e.g., window_size, theme_preference)
    - Skip desktop-specific settings when syncing to Android
    - _Requirements: 20.5, 20.6_

- [x] 18. Implement bandwidth-saver mode in TypeScript
  - [ ] 18.1 Add bandwidth-saver toggle to UI in React
    - Create toggle switch component in settings panel
    - Store bandwidth_saver_enabled flag in configuration state
    - When enabled, disable automatic file sync by stopping file watcher
    - When enabled, disable clipboard sync by not sending clipboard events
    - Display current data usage prominently when bandwidth-saver is enabled
    - Show warning notification when data usage exceeds configured threshold from settings
    - _Requirements: 12.4, 12.7_

- [x] 19. Build agent session management UI in React TypeScript
  - [ ] 19.1 Create session UI components in React
    - Create SessionList component displaying list of active sessions with Session_IDs and timestamps
    - Add "New Session" button creating new chat session
    - Add "Resume Session" buttons for each existing session loading session history
    - Store sessions in React state and localStorage for persistence
    - _Requirements: 9.1, 9.2, 9.5, 9.7_
  
  - [ ] 19.2 Implement typing indicators in React
    - Display animated typing indicator when agent turn is in progress (listening to tool_start events)
    - Show current tool execution status with tool name and progress percentage from tool_progress events
    - Hide indicator when tool_complete or tool_error event received
    - _Requirements: 9.8_
  
  - [ ] 19.3 Handle session context in TypeScript
    - Include Session_ID in POST /api/chat request body
    - If no Session_ID provided, extract new Session_ID from response and store
    - Maintain message history array up to 40 messages per session in memory and localStorage
    - Display session expiration warning when last activity timestamp is >20 hours old
    - Remove expired sessions (>24 hours inactive) from UI and localStorage
    - _Requirements: 9.3, 9.4, 9.6_

- [ ] 20. Checkpoint - Core functionality verification
  - Verify pairing flow works end-to-end: initiate pairing, confirm code, receive and store token
  - Test WebSocket connection establishment and authentication
  - Validate event streaming by triggering tool execution and observing events
  - Test tool invocation with progress tracking and cancellation
  - Ensure reconnection logic handles network failures with exponential backoff
  - Verify connection fallback attempts TCP then ADB
  - Ask the user if questions arise
  - _Checklist: forge-desktop/docs/VERIFICATION.md_
  - _Checklist: forge-desktop/docs/VERIFICATION.md_
  - _Checklist: forge-desktop/docs/VERIFICATION.md_

- [x]* 21. Write integration tests in Kotlin and TypeScript (TS verification scripts for metrics/recovery; full suite remains manual per repo convention)
  - [ ]* 21.1 Test pairing flow
    - Write test calling POST /api/pairing/initiate and verifying 6-digit code response
    - Test POST /api/pairing/confirm with valid code returns JWT token
    - Verify token storage in keychain using mock keychain service
    - Test pairing code expiration after 5 minutes
    - _Requirements: 2.1-2.7_
  
  - [ ]* 21.2 Test tool execution
    - Test POST /api/tool returns operation ID immediately
    - Poll GET /api/tool/{opId}/status and verify status transitions
    - Test progress updates via WebSocket tool_progress events
    - Test POST /api/tool/{opId}/cancel terminates operation
    - _Requirements: 4.1-4.8_
  
  - [ ]* 21.3 Test file synchronization
    - Test uploading file in 1MB chunks via POST /api/sync/upload
    - Verify checksum validation on Android
    - Test downloading file via GET /api/sync/download
    - Test conflict resolution with simultaneous modifications
    - _Requirements: 5.1-5.8_
  
  - [ ]* 21.4 Test clipboard sync
    - Test copying text on desktop sends clipboard event via WebSocket
    - Verify Android clipboard updates within 2 seconds
    - Test copying on Android sends event to desktop
    - Verify desktop clipboard updates
    - Test image clipboard with PNG format
    - _Requirements: 6.1-6.7_
  
  - [ ]* 21.5 Test notification bridging
    - Trigger Android notification using NotificationManager test helper
    - Verify desktop receives notification event within 3 seconds
    - Test displaying notification using desktop notification API
    - Test clicking notification action sends event back to Android
    - Verify action is triggered on Android
    - _Requirements: 7.1-7.7_
  
  - [ ]* 21.6 Test WebSocket reconnection
    - Simulate network interruption by closing WebSocket connection
    - Verify reconnection attempts with exponential backoff timing
    - Confirm events resume after reconnection
    - Test subscription persistence across reconnections
    - _Requirements: 8.6, 14.1_
  
  - [ ]* 21.7 Test multi-client connections
    - Connect 3 desktop clients simultaneously with same token
    - Verify all clients receive broadcasted events
    - Connect 6th client and verify rejection with connection limit error
    - Test connection limit of 5 per token
    - _Requirements: 17.1-17.7_

- [x]* 22. Enhance mock server for testing in Python (pairing endpoints + async tool ops + optional WS events with --ws)
  - [ ]* 22.1 Add WebSocket endpoint to mock_server.py
    - Implement WebSocket upgrade handler at /api/events using websockets library
    - Simulate authentication by checking Authorization header
    - Support event subscription messages
    - Broadcast test events to connected clients
    - _Requirements: 8.1-8.8_
  
  - [ ]* 22.2 Add pairing endpoints to mock server
    - Implement POST /api/pairing/initiate generating random 6-digit code
    - Implement POST /api/pairing/confirm validating code and returning mock JWT token
    - Store pairing codes in memory dict with timestamp
    - _Requirements: 2.1-2.7_
  
  - [ ]* 22.3 Add async tool operation endpoints to mock server
    - Modify POST /api/tool to return operation ID
    - Implement GET /api/tool/{opId}/status returning mock operation state
    - Implement POST /api/tool/{opId}/cancel setting status to cancelled
    - Simulate progress updates by incrementing percent over time
    - _Requirements: 4.1-4.8_

- [ ] 23. Final checkpoint - Full system verification
  - Run all integration tests from task 21 and verify they pass
  - Verify all 20 requirements are met by testing each acceptance criterion
  - Test complete user workflows end-to-end: pair device, execute tools, sync files, sync clipboard, receive notifications
  - Test on all supported platforms: macOS, Windows, Linux
  - Verify performance meets requirements: health check 10s, clipboard sync 2s, notification 3s, event broadcast 500ms
  - Ensure all tests pass, ask the user if questions arise

- [x]* 24. Write documentation (forge-desktop/docs/ - setup, user guide, troubleshooting, API reference, WS protocol, tool dev, architecture, verification)
  - [ ]* 24.1 Write user documentation
    - Write setup guide for first-time pairing with screenshots
    - Document feature usage: connection management, tool execution, file sync, clipboard, notifications
    - Write troubleshooting guide covering common issues: connection failures, pairing errors, sync conflicts
    - Include FAQ section
    - _All Requirements_
  
  - [ ]* 24.2 Write developer documentation
    - Document API specifications for all endpoints with request/response examples
    - Write WebSocket protocol documentation with message format examples
    - Write integration guide for desktop tool development with code samples
    - Create architecture diagrams showing component interactions
    - Document configuration options and environment variables
    - _All Requirements_

## Notes

- This is a large-scale integration project spanning Android (Kotlin), Desktop Frontend (TypeScript/React), and Desktop Backend (Rust/Tauri)
- Core infrastructure tasks (1-4) establish the foundation and should be completed first
- WebSocket event streaming (tasks 2, 6) is critical for real-time features
- File sync (task 9) and clipboard sync (task 10) can be developed in parallel after core infrastructure
- Tasks marked with `*` are optional (primarily testing and documentation) and can be skipped for faster MVP
- Integration tests (task 21) should run continuously as features are implemented
- The design document estimates this project at 7 weeks of development time
- Checkpoints (tasks 20, 23) are included at logical breakpoints to verify functionality before proceeding
- All tasks reference specific requirements for traceability
- Each task includes concrete implementation details: specific libraries, APIs, algorithms, and data structures
- Tasks build incrementally with each checkpoint validating core functionality through code

## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "tasks": ["1.1", "13.1"]
    },
    {
      "id": 1,
      "tasks": ["1.2", "13.2", "13.3"]
    },
    {
      "id": 2,
      "tasks": ["1.3", "1.4", "1.5", "2.1", "3.1", "4.1"]
    },
    {
      "id": 3,
      "tasks": ["2.2", "3.2", "4.2"]
    },
    {
      "id": 4,
      "tasks": ["2.3", "3.3", "4.3", "5.1"]
    },
    {
      "id": 5,
      "tasks": ["2.4", "3.4", "5.2", "6.1", "7.1"]
    },
    {
      "id": 6,
      "tasks": ["5.3", "6.2", "7.2", "8.1"]
    },
    {
      "id": 7,
      "tasks": ["7.3", "8.2", "9.1", "14.1"]
    },
    {
      "id": 8,
      "tasks": ["8.3", "8.4", "9.2", "9.3", "10.1", "14.2"]
    },
    {
      "id": 9,
      "tasks": ["9.4", "10.2", "11.1", "12.1", "14.3"]
    },
    {
      "id": 10,
      "tasks": ["9.5", "10.3", "11.2", "12.2", "14.4", "15.1"]
    },
    {
      "id": 11,
      "tasks": ["9.6", "11.3", "12.3", "15.2", "16.1"]
    },
    {
      "id": 12,
      "tasks": ["11.4", "15.3", "16.2", "17.1"]
    },
    {
      "id": 13,
      "tasks": ["16.3", "17.2", "18.1", "19.1"]
    },
    {
      "id": 14,
      "tasks": ["16.4", "17.3", "19.2", "19.3"]
    },
    {
      "id": 15,
      "tasks": ["21.1", "21.2", "21.3", "21.4", "21.5", "21.6", "21.7"]
    },
    {
      "id": 16,
      "tasks": ["22.1", "22.2", "22.3"]
    },
    {
      "id": 17,
      "tasks": ["24.1", "24.2"]
    }
  ]
}
```
