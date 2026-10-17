using System.Windows;
using System.Windows.Controls;
using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using MessageBox = System.Windows.MessageBox;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Presentation;

internal sealed class ReminderPopupWindow : Window
{
    public ReminderPopupWindow(ReminderPresentation presentation)
    {
        Title = $"Omni {presentation.Item.Kind}";
        Width = 380;
        SizeToContent = SizeToContent.Height;
        Topmost = true;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var panel = new StackPanel { Margin = new Thickness(18) };
        panel.Children.Add(new TextBlock { Text = presentation.Item.Title, FontSize = 20, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 16) });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal };
        AddButton(presentation.Item.Kind == ReminderKind.Task ? "Done" : "Dismiss", "done");
        AddButton(presentation.Item.Kind == ReminderKind.Task ? "Tomorrow" : "Snooze 10m", presentation.Item.Kind == ReminderKind.Task ? "tomorrow" : "snooze");
        panel.Children.Add(buttons);
        Content = panel;
        void AddButton(string label, string action)
        {
            var button = new Button { Content = label, Padding = new Thickness(12, 6, 12, 6), Margin = new Thickness(0, 0, 8, 0) };
            button.Click += async (_, _) =>
            {
                buttons.IsEnabled = false;
                try { await presentation.Act(action); Close(); }
                catch (Exception ex) { buttons.IsEnabled = true; MessageBox.Show(this, ex.Message, "Reminder action failed"); }
            };
            buttons.Children.Add(button);
        }
    }
}
