cask "zync" do
  version "0.2"
  arch arm: "aarch64", intel: "x64"

  sha256 :no_check

  url "https://github.com/njreid/zync/releases/download/v#{version}/zync-#{version}-macos-#{arch}.dmg",
      verified: "github.com/njreid/zync/"
  name "zync"
  desc "Desktop client for a paired zync phone"
  homepage "https://github.com/njreid/zync"

  app "zync.app"

  zap trash: [
    "~/Library/Application Support/dev.njr.zync.desktop",
    "~/Library/Caches/dev.njr.zync.desktop",
    "~/Library/Logs/dev.njr.zync.desktop",
    "~/Library/Preferences/dev.njr.zync.desktop.plist",
  ]
end
