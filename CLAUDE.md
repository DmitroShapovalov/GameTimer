# GameTimer

Windows WPF-приложение для родительского контроля игрового времени.

## Документация

Техническое задание и спецификация проекта: [.agent/SPEC.md](.agent/SPEC.md)

## Стек

- .NET 8 LTS, C#, WPF
- Публикация: single-file self-contained exe (win-x64)
- Без внешних зависимостей (только System.Text.Json, System.Media.SoundPlayer, WinForms NotifyIcon для трея)

## Структура

- `.agent/` — документация и спецификации проекта
- `src/` — исходный код приложения (при реализации)

## Сборка

```
dotnet publish -r win-x64 --self-contained -p:PublishSingleFile=true
```
