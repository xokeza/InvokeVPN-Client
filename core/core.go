// Package androidcore exposes Xray to Android through gomobile.
package androidcore

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	xray "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var tunnel struct {
	sync.Mutex
	instance *xray.Instance
}

func newCore(config string) (*xray.Instance, error) {
	parsed, err := xray.LoadConfig("json", strings.NewReader(config))
	if err != nil {
		return nil, errors.New("Xray отклонил параметры узла")
	}
	instance, err := xray.New(parsed)
	if err != nil {
		return nil, errors.New("Не удалось подготовить Xray")
	}
	if err := instance.Start(); err != nil {
		instance.Close()
		return nil, errors.New("Не удалось запустить Xray")
	}
	return instance, nil
}

// Start starts the tunnel with a VpnService-owned descriptor. The caller keeps it open.
func Start(config string, fd int) error {
	tunnel.Lock()
	defer tunnel.Unlock()
	if tunnel.instance != nil {
		return errors.New("VPN уже запущен")
	}
	os.Setenv("xray.tun.fd", strconv.Itoa(fd))
	instance, err := newCore(config)
	os.Unsetenv("xray.tun.fd")
	if err == nil {
		tunnel.instance = instance
	}
	return err
}

func Stop() {
	tunnel.Lock()
	defer tunnel.Unlock()
	if tunnel.instance != nil {
		tunnel.instance.Close()
		tunnel.instance = nil
	}
}

func Bytes(direction string) int64 {
	tunnel.Lock()
	defer tunnel.Unlock()
	if tunnel.instance == nil || (direction != "uplink" && direction != "downlink") {
		return 0
	}
	manager := tunnel.instance.GetFeature(stats.ManagerType()).(stats.Manager)
	if counter := manager.GetCounter("outbound>>>vpn>>>traffic>>>" + direction); counter != nil {
		return counter.Value()
	}
	return 0
}

// Probe measures a real HTTPS request using only this node's outbound.
func Probe(config, target string, timeoutMillis int, head bool) (int64, error) {
	instance, err := newCore(config)
	if err != nil {
		return 0, err
	}
	defer instance.Close()
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
			dest, err := xnet.ParseDestination("tcp:" + address)
			if err != nil {
				return nil, err
			}
			return xray.Dial(ctx, instance, dest)
		},
		DisableKeepAlives: true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeoutMillis) * time.Millisecond,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	started := time.Now()
	method := http.MethodGet
	if head {
		method = http.MethodHead
	}
	request, err := http.NewRequest(method, target, nil)
	if err != nil {
		return 0, errors.New("Некорректный адрес проверки")
	}
	response, err := client.Do(request)
	if err != nil {
		return 0, errors.New("Узел не ответил на HTTPS-проверку")
	}
	defer response.Body.Close()
	if response.StatusCode != 204 && response.StatusCode != 200 {
		return 0, errors.New("Неожиданный HTTP-статус проверки")
	}
	if _, err := io.Copy(io.Discard, io.LimitReader(response.Body, 4096)); err != nil {
		return 0, errors.New("Проверочный ответ оборвался")
	}
	return time.Since(started).Milliseconds(), nil
}
