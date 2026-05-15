"""
Butler Android Bridge Module
This module provides the interface between Android (Kotlin) and Butler core.
It acts as a bridge that can be called from Chaquopy.
"""

import json
import os
import sys
from typing import Dict, Any, Optional

# Try to import Butler core components
try:
    from butler.jarvis import Jarvis
    from butler.config import Config
    BUTLER_AVAILABLE = True
except ImportError:
    BUTLER_AVAILABLE = False
    print("Warning: Butler core not available, running in standalone mode")


# Global state
_jarvis: Optional[Jarvis] = None
_settings: Dict[str, Any] = {
    "api_key": "",
    "voice_enabled": True,
    "dark_mode": False,
    "language": "en",
    "speech_rate": 1.0,
    "wake_word": "Butler"
}


def initialize() -> bool:
    """
    Initialize Butler core. Called when Android app starts.
    Returns True if successful, False otherwise.
    """
    global _jarvis

    if not BUTLER_AVAILABLE:
        print("Butler core not available, using mock mode")
        return True

    try:
        # Load config from environment or defaults
        config = Config.from_env()

        # Initialize Jarvis
        _jarvis = Jarvis(config)
        _jarvis.initialize()

        print("Butler initialized successfully")
        return True

    except Exception as e:
        print(f"Failed to initialize Butler: {e}")
        return False


def process_message(message: str) -> str:
    """
    Process a user message and return Butler's response.
    This is the main entry point for chat functionality.

    Args:
        message: User's text message

    Returns:
        Butler's response as a string
    """
    global _jarvis

    if not _jarvis:
        # Mock response if Butler not initialized
        return f"Echo: {message}\n\n(Butler is not initialized)"

    try:
        response = _jarvis.process(message)
        return response
    except Exception as e:
        return f"Error processing message: {str(e)}"


def get_settings() -> Dict[str, Any]:
    """
    Get current Butler settings.

    Returns:
        Dictionary of settings
    """
    return _settings.copy()


def update_settings(settings: Dict[str, Any]) -> bool:
    """
    Update Butler settings.

    Args:
        settings: Dictionary of settings to update

    Returns:
        True if successful
    """
    global _settings

    _settings.update(settings)

    if _jarvis:
        try:
            _jarvis.update_config(settings)
        except Exception as e:
            print(f"Failed to update config: {e}")

    return True


def start_voice() -> bool:
    """
    Start voice listening.

    Returns:
        True if started successfully
    """
    if not _jarvis:
        return False

    try:
        _jarvis.start_voice_input()
        return True
    except Exception as e:
        print(f"Failed to start voice: {e}")
        return False


def stop_voice() -> str:
    """
    Stop voice listening and return transcribed text.

    Returns:
        Transcribed text
    """
    if not _jarvis:
        return ""

    try:
        text = _jarvis.stop_voice_input()
        return text
    except Exception as e:
        print(f"Failed to stop voice: {e}")
        return ""


def speak(text: str) -> bool:
    """
    Text-to-speech.

    Args:
        text: Text to speak

    Returns:
        True if successful
    """
    if not _jarvis or not _settings.get("voice_enabled", True):
        return False

    try:
        _jarvis.speak(text)
        return True
    except Exception as e:
        print(f"Failed to speak: {e}")
        return False


def execute_code(code: str) -> str:
    """
    Execute Python code in sandbox.

    Args:
        code: Python code to execute

    Returns:
        Execution result
    """
    if not _jarvis:
        return "Code executor not available"

    try:
        result = _jarvis.execute_code(code)
        return str(result)
    except Exception as e:
        return f"Error: {str(e)}"


def call_plugin(plugin_name: str, action: str, params: Dict[str, Any]) -> str:
    """
    Call a plugin action.

    Args:
        plugin_name: Name of the plugin
        action: Action to call
        params: Parameters for the action

    Returns:
        JSON result
    """
    if not _jarvis:
        return json.dumps({"error": "Plugin system not available"})

    try:
        result = _jarvis.call_plugin(plugin_name, action, params)
        if isinstance(result, dict):
            return json.dumps(result)
        return str(result)
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_plugins() -> list:
    """
    Get list of available plugins.

    Returns:
        List of plugin names
    """
    if not _jarvis:
        return []

    try:
        return _jarvis.get_plugins()
    except Exception:
        return []


def cleanup():
    """
    Cleanup resources. Called when app closes.
    """
    global _jarvis

    if _jarvis:
        try:
            _jarvis.cleanup()
        except Exception as e:
            print(f"Cleanup error: {e}")

    _jarvis = None
    print("Butler cleanup complete")
