#!/bin/sh
# Checks the YouTube / Twitch keys in config.properties against the same official endpoints the app uses.
# Usage: sh tools/test-streams.sh   (needs curl; prints the first 3 titles of each, or the error)
cd "$(dirname "$0")/.." || exit 1
prop() { sed -n "s/^$1=//p" config.properties | tail -1 | tr -d '\r'; }
YT=$(prop youtube_key); ID=$(prop twitch_client_id); SECRET=$(prop twitch_client_secret)
REGION=$(prop tmdb_region); REGION=${REGION:-IT}; LANG=$(prop language); [ "$LANG" = auto ] || [ -z "$LANG" ] && LANG=it
if [ -n "$YT" ]; then
  echo "== YouTube mostPopular regionCode=$REGION hl=$LANG"
  curl -s "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=3&regionCode=$REGION&hl=$LANG&key=$YT" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("error",{}).get("message") or "\n".join(i["snippet"]["channelTitle"]+" - "+i["snippet"]["localized"]["title"] for i in d["items"]))'
else echo "youtube_key empty: YouTube tab stays hidden"; fi
if [ -n "$ID" ] && [ -n "$SECRET" ]; then
  TOKEN=$(curl -s -X POST https://id.twitch.tv/oauth2/token -d "client_id=$ID&client_secret=$SECRET&grant_type=client_credentials" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("access_token",""))')
  [ -z "$TOKEN" ] && { echo "Twitch: no token (check client id/secret)"; exit 1; }
  for q in "language=$LANG&" ""; do
    echo "== Twitch streams ${q:-overall top}"
    curl -s "https://api.twitch.tv/helix/streams?${q}first=3" -H "Client-Id: $ID" -H "Authorization: Bearer $TOKEN" \
      | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("message") or "\n".join("%s (%s, %d viewers): %s"%(s["user_name"],s["game_name"],s["viewer_count"],s["title"]) for s in d["data"]))'
  done
else echo "twitch_client_id/secret empty: Twitch tab stays hidden"; fi
