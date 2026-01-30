using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Controls;
using System.Collections.Generic;
using System.Linq;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Presentation
{
    public class ActionNameConverter : IMultiValueConverter
    {
        public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
        {
            if (values.Length < 1 || !(values[0] is string action)) return "";
            
            if (action.StartsWith("LAUNCH_PROJECT_"))
            {
                var idStr = action.Substring("LAUNCH_PROJECT_".Length);
                if (Guid.TryParse(idStr, out var id) && values.Length > 1 && values[1] is IEnumerable<Project> projects)
                {
                    var project = projects.FirstOrDefault(p => p.Id == id);
                    if (project != null)
                    {
                        return $"Launch Project: {project.Name}";
                    }
                }
                return $"Launch Project: {idStr}";
            }
            return action;
        }

        public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class StateToBrushConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool b && b)
            {
                return new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 122, 204)); // Blue
            }
            return new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(62, 62, 66)); // Default Gray
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class InverseBooleanToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool b)
            {
                if (parameter?.ToString() == "Inverse")
                    return b ? Visibility.Collapsed : Visibility.Visible;
                return b ? Visibility.Visible : Visibility.Collapsed;
            }
            return Visibility.Visible;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class EnumToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value == null || parameter == null) return Visibility.Collapsed;
            string checkValue = value.ToString() ?? "";
            string targetValue = parameter.ToString() ?? "";
            return checkValue.Equals(targetValue, StringComparison.OrdinalIgnoreCase) ? Visibility.Visible : Visibility.Collapsed;
        }

                public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)

                {

                    throw new NotImplementedException();

                }

            }

        

            public class ProjectIndexConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is FrameworkElement element && element.DataContext is Project project)
            {
                var itemsControl = FindParent<ItemsControl>(element);
                if (itemsControl != null)
                {
                    int index = itemsControl.Items.IndexOf(project);
                    if (index >= 0 && index < 9)
                    {
                        return (index + 1).ToString();
                    }
                }
            }
            return "";
        }

        private T? FindParent<T>(DependencyObject child) where T : DependencyObject
        {
            DependencyObject parentObject = System.Windows.Media.VisualTreeHelper.GetParent(child);
            if (parentObject == null) return null;
            if (parentObject is T parent) return parent;
            return FindParent<T>(parentObject);
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class CategoryExpansionConverter : IMultiValueConverter

            {

                public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)

                {

                    if (values.Length == 2 && values[0] is string category && values[1] is MainViewModel vm)

                    {

                        return vm.GetCategoryExpansionState(category);

                    }

                    return true;

                }

        

                public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture)

                {

                    throw new NotImplementedException();

                }

            }

        }

        