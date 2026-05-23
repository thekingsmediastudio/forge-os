package com.forge.os.domain.security

/**
 * Vigilance levels for the trust scoring system.
 * Higher vigilance means more security restrictions and checks.
 */
enum class VigilanceLevel {
    /** Low vigilance - high trust environment (score >= 80) */
    LOW,
    
    /** Normal vigilance - moderate trust (score 50-79) */
    NORMAL,
    
    /** High vigilance - low trust environment (score 30-49) */
    HIGH,
    
    /** Paranoid vigilance - very low trust, maximum security (score < 30) */
    PARANOID
}
