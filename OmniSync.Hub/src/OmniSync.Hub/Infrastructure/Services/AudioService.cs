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

        public virtual float GetMasterVolume()
        {
            return _defaultDevice.AudioEndpointVolume.MasterVolumeLevelScalar * 100.0f;
        }

        public virtual void SetMasterVolume(float volumePercentage)
        {
            // Ensure volumePercentage is between 0 and 100
            volumePercentage = Math.Clamp(volumePercentage, 0f, 100f);
            _defaultDevice.AudioEndpointVolume.MasterVolumeLevelScalar = volumePercentage / 100.0f;
        }

        public virtual bool ToggleMute()
        {
            _defaultDevice.AudioEndpointVolume.Mute = !_defaultDevice.AudioEndpointVolume.Mute;
            return _defaultDevice.AudioEndpointVolume.Mute;
        }

        public virtual bool IsMuted()
        {
            return _defaultDevice.AudioEndpointVolume.Mute;
        }

        public virtual void PlaySound(string filePath)
        {
            if (!System.IO.File.Exists(filePath)) return;

            Task.Run(() =>
            {
                try
                {
                    if (filePath.EndsWith(".wav", StringComparison.OrdinalIgnoreCase))
                    {
                        using (var player = new System.Media.SoundPlayer(filePath))
                        {
                            player.Play();
                        }
                    }
                    else
                    {
                        using (var audioFile = new NAudio.Wave.AudioFileReader(filePath))
                        using (var outputDevice = new NAudio.Wave.WaveOutEvent())
                        {
                            outputDevice.Init(audioFile);
                            outputDevice.Play();
                            while (outputDevice.PlaybackState == NAudio.Wave.PlaybackState.Playing)
                            {
                                System.Threading.Thread.Sleep(100);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error playing sound {filePath}: {ex.Message}");
                }
            });
        }

        public virtual void PlayBlip()
        {
            string rootPath = AppContext.BaseDirectory;
            // Search for Resources folder up the tree
            string? resourcesPath = null;
            var current = new System.IO.DirectoryInfo(rootPath);
            while (current != null)
            {
                var potential = System.IO.Path.Combine(current.FullName, "Resources");
                if (System.IO.Directory.Exists(potential))
                {
                    resourcesPath = potential;
                    break;
                }
                current = current.Parent;
            }

            if (resourcesPath != null)
            {
                // Try the specific requested sound first (new split sounds)
                string requestedSound = System.IO.Path.Combine(resourcesPath, "SoundEffects", "Scroll_03.wav");
                if (System.IO.File.Exists(requestedSound))
                {
                    PlaySound(requestedSound);
                    return;
                }

                // Fallback to the old sound pack path
                string oldSound = System.IO.Path.Combine(resourcesPath, "UI", "UI_SoundPack13_Scroll_version05.wav");
                if (System.IO.File.Exists(oldSound))
                {
                    PlaySound(oldSound);
                    return;
                }

                // Fallback to chime.mp3 then shut1min.wav
                string chime = System.IO.Path.Combine(resourcesPath, "Alarms", "chime.mp3");
                if (System.IO.File.Exists(chime))
                {
                    PlaySound(chime);
                }
                else
                {
                    string fallback = System.IO.Path.Combine(resourcesPath, "shut1min.wav");
                    PlaySound(fallback);
                }
            }
        }

        public void Dispose()
        {
            _defaultDevice?.Dispose();
        }
    }
}
