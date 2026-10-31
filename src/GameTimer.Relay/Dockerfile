# The relay image (docs/protocol.md §10). Build from the repo root:
#   docker build -f src/GameTimer.Relay/Dockerfile .
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY src/GameTimer.Protocol/ src/GameTimer.Protocol/
COPY src/GameTimer.Relay/ src/GameTimer.Relay/
RUN dotnet publish src/GameTimer.Relay/GameTimer.Relay.csproj -c Release -r linux-x64 --self-contained false -o /app

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /app .
# One small machine (256 MB): workstation GC with a hard heap cap, so the process fails loudly instead of
# being killed by the kernel; the relay's own budgets (RelayOptions) stay well below it
ENV ASPNETCORE_HTTP_PORTS=8080 \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_gcServer=0 \
    DOTNET_GCHeapHardLimit=0xA000000
EXPOSE 8080
USER app
ENTRYPOINT ["dotnet", "GameTimer.Relay.dll"]
