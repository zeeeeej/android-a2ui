#!/usr/bin/env ruby
# Compare in memory; never print the key or any matching binary content.
require 'open3'

Dir.chdir(File.expand_path('..', __dir__))
key = File.read('.key').strip
abort 'Missing local key' if key.empty?

def dex_bytes(path)
  bytes, status = Open3.capture2('unzip', '-p', path, 'classes*.dex')
  abort "Cannot inspect #{path}" unless status.success?
  bytes.b
end

debug = dex_bytes('app/build/outputs/apk/debug/app-debug.apk')
release = dex_bytes('app/build/outputs/apk/release/app-release-unsigned.apk')
abort 'Debug key injection failed' unless debug.include?(key.b)
abort 'Release unexpectedly contains the debug key' if release.include?(key.b)
puts 'PASS: Debug contains the configured key; Release DEX does not.'
