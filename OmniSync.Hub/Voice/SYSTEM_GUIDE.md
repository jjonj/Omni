# Jarvis Voice System - Technical Guide

This document outlines the architecture and operation of the localized AI voice system developed for the Jarvis PoC.

## 🏗️ System Architecture

The system consists of three main components working in a sequential loop:

1.  **ASR (Speech-to-Text):** Powered by `parakeet-rs` using NVIDIA's Parakeet FastConformer-TDT model (ONNX Runtime).
2.  **TTS (Text-to-Speech):** Powered by `supertonic`, an ultra-fast on-device synthesis engine.
3.  **Controller (The Jarvis Loop):** A set of Python scripts that manage the transition between listening, recording, transcribing, and speaking.

---

## 📁 Folder Structure

*   `parakeet-rs/`: Rust implementation of NVIDIA Parakeet.
    *   `tdt/`: Contains the large ONNX model files and vocabulary.
*   `supertonic/`: The TTS engine and its assets (ONNX models and voice styles).
*   `JarvisPoC/`: The primary workspace for the active assistant.
    *   `custom_samples/`: Your 10 "Jarvis" recordings.
    *   `fingerprint.pkl`: Your mathematical voice identity for wake-word detection.
    *   `scripts`: `wait_for_jarvis.py`, `recorder.py`, `transcribe.py`, `speak.py`.

---

## 🔄 The "Jarvis" Loop Procedure

To run the system, we follow this manual back-and-forth loop controlled by the AI (Gemini):

### 1. The Listener (`wait_for_jarvis.py`)
*   **Action:** Monitors the microphone in a low-resource mode.
*   **Logic:** Uses a background thread to compare live audio against your 10 stored fingerprints using Cosine Similarity.
*   **Trigger:** When a match > 0.95 confidence is found, it spawns the recorder and exits.

### 2. The Recorder (`recorder.py`)
*   **Action:** Runs in the background to capture your actual command.
*   **Logic:** Uses an Energy-based Voice Activity Detector (VAD). It records until it detects 2.0 seconds of silence or reaches a 30-second limit.
*   **Output:** Saves `JarvisPoC/speech_input.wav`.

### 3. The Transcriber (`transcribe.py`)
*   **Action:** Waits for the recorder to finish, then runs the ASR engine.
*   **Logic:** Executes `cargo run` inside `parakeet-rs` to process the WAV file using the GPU (CUDA).
*   **Output:** Writes your words to `JarvisPoC/transcript.txt`.

### 4. The Voice (`speak.py`)
*   **Action:** Gemini reads the transcript, formulates a response, and triggers the voice.
*   **Logic:** Feeds the text to `supertonic` and plays the resulting audio internally using `sounddevice`.

---

## ⚙️ Configuration & Sensitivity

If the system is not stopping or triggering correctly, adjust these files:

*   **Wake-Word Sensitivity:** In `wait_for_jarvis.py`, adjust `THRESHOLD`. Higher (0.99) is stricter, lower (0.90) is easier to trigger.
*   **Silence Detection:** In `recorder.py`, adjust `SILENCE_THRESHOLD`. If it never stops recording, your room might be too noisy—increase this value (e.g., to `0.02`).
*   **Voice Style:** In `speak.py`, change the `--voice` argument (M1-M5 for male, F1-F5 for female).

---

## 🚀 How to Start
1.  Ensure you are in the `Voice` root directory.
2.  Run the listener: `python JarvisPoC/wait_for_jarvis.py`
3.  Say "**Jarvis**" and wait for the "Recording started" message.
4.  Speak your command.
5.  Run `python JarvisPoC/transcribe.py` to process the result.
