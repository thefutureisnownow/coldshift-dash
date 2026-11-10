#!/bin/zsh
# Watch the dash app on a phone in the car over Wi-Fi adb.
#
#   car-adb.sh arm [serial]          phone on USB: adbd listens on 5555 until the phone reboots
#                                    (serial: from `adb devices`, when more than one is plugged in)
#   car-adb.sh connect ip            over Wi-Fi
#   car-adb.sh watch ip              the OBD link and ELM327 traffic, live, also saved to car-logs/
#   car-adb.sh bt ip                 what Android thinks of Bluetooth: paired devices, connections
#   car-adb.sh shot ip               a screenshot into car-logs/
#   car-adb.sh survey ip             asks the car once for every reading it lists, its VIN and calibration (raw replies)
#
# ip may be ip:port, for Android 11+ wireless debugging (Developer options, Wireless debugging shows it;
# `adb mdns services` finds it too). Plain ip means port 5555, armed over USB. CAR_ADB_IP sets a default.
#
# Away from your Wi-Fi: turn on the phone's hotspot, join it from the computer, and use the
# phone's hotspot address (usually 192.168.x.1; `car-adb.sh connect` lists the routes if it fails).

ADB=${ADB:-$(command -v adb || echo ${ANDROID_HOME:-~/Library/Android/sdk}/platform-tools/adb)}
USB=${2:+-s $2}
IP=${2:-$CAR_ADB_IP}
case $IP in *:*) DEV=$IP ;; *) DEV=$IP:5555 ;; esac
LOGS=${0:A:h}/../car-logs
stamp() { date +%Y%m%d-%H%M%S }
CMD=$1
need_ip() { [ -n "$IP" ] || { echo "which phone? car-adb.sh $CMD ip (or set CAR_ADB_IP)" >&2; exit 1 } }

connect() {
    need_ip
    mkdir -p $LOGS
    $ADB connect $DEV | grep -q "connected to" && return 0
    echo "cannot reach $DEV: is the phone on the same network, and was it armed since it last rebooted?" >&2
    netstat -rn -f inet | grep -E "default|192\.168\." | head -5 >&2
    return 1
}

case $1 in
arm)
    $ADB ${=USB} tcpip 5555 && sleep 2
    $ADB ${=USB} shell ip -4 addr show wlan0 | grep inet
    ;;
connect)
    connect && $ADB -s $DEV shell getprop ro.product.model
    ;;
watch)
    connect || exit 1
    f=$LOGS/obd-$(stamp).log
    echo "saving to $f (ctrl-c to stop)"
    # ObdTrace: every command and reply; Obd: states; BtLink: each way of connecting; plus Android's own Bluetooth socket lines
    $ADB -s $DEV logcat -v time ObdTrace:I Obd:I BtLink:I Devices:I 'BluetoothSocket:*' 'bt_rfcomm:*' AndroidRuntime:E '*:S' | tee $f
    ;;
bt)
    connect || exit 1
    $ADB -s $DEV shell dumpsys bluetooth_manager | grep -iE "enabled|state:|name:|address|bonded|connected|rfcomm|konnwei|obd" | head -60
    ;;
survey)
    connect || exit 1
    f=$LOGS/survey-$(stamp).log
    $ADB -s $DEV logcat -c
    $ADB -s $DEV shell am broadcast -a racing.coldshift.dash.OBD_ASK --es cmd survey | tail -1
    sleep 12
    $ADB -s $DEV logcat -d -v time ObdTrace:I '*:S' > $f && echo $f
    ;;
shot)
    connect || exit 1
    f=$LOGS/shot-$(stamp).png
    $ADB -s $DEV exec-out screencap -p > $f && echo $f
    ;;
*)
    sed -n '2,17p' $0 | sed 's/^# \{0,1\}//'
    ;;
esac
