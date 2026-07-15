import re
import urllib.request

ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15"

req = urllib.request.Request("https://music.apple.com/", headers={"User-Agent": ua})
html = urllib.request.urlopen(req).read().decode()

script = re.search(r'crossorigin src="(/assets/index.+?\.js)"', html)
if not script:
    print("ERROR: could not find script tag")
    exit(1)

req2 = urllib.request.Request("https://music.apple.com" + script.group(1), headers={"User-Agent": ua})
js = urllib.request.urlopen(req2).read().decode()

token = re.search(r'(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)', js)
if not token:
    print("ERROR: could not find JWT in script")
    exit(1)

print(token.group(1))
