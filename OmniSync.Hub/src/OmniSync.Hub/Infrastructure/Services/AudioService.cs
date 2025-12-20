using System;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class AudioService : IDisposable
    {
        private readonly MMDevice _defaultDevice;

        public AudioService()
        {
            var enumerator = new MMDeviceEnumerator();
            _defaultDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }

        public float GetMasterVolume()
        {
            return _defaultDevice.AudioEndpointVolume.MasterVolumeLevelScalar * 100.0f;
        }

        public void SetMasterVolume(float volumePercentage)
        {
            // Ensure volumePercentage is between 0 and 100
            volumePercentage = Math.Clamp(volumePercentage, 0f, 100f);
            _defaultDevice.AudioEndpointVolume.MasterVolumeLevelScalar = volumePercentage / 100.0f;
        }

        public bool ToggleMute()
        {
            _defaultDevice.AudioEndpointVolume.Mute = !_defaultDevice.AudioEndpointVolume.Mute;
            return _defaultDevice.AudioEndpointVolume.Mute;
        }

        public bool IsMuted()
        {
            return _defaultDevice.AudioEndpointVolume.Mute;
        }

        public void PlaySound(string filePath)
        {
            try
            {
                if (System.IO.File.Exists(filePath))
                {
                    using (var player = new System.Media.SoundPlayer(filePath))
                    {
                        player.PlaySync();
                    }
                }
            }
            catch (Exception ex)
            {
                // Log error or handle silently for now as requested for high privilege personal ecosystem
                Console.WriteLine($"Error playing sound {filePath}: {ex.Message}");
            }
        }

        public void Dispose()
        {
            _defaultDevice?.Dispose();
        }
    }
}
