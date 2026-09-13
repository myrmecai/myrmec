// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** Host-control socket handler (§4.2) — message handling lands with Task 3. */
@Component
public class HostControlWebSocketHandler extends AbstractWebSocketHandler {
}
