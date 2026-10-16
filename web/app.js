(() => {
  const app = document.getElementById("app");
  const toastEl = document.getElementById("toast");

  const ui = {
    name: localStorage.getItem("tt-name") || "",
    format: localStorage.getItem("tt-format") || "duel",
    deckId: localStorage.getItem("tt-deck") || "",
    deckQuery: "",
    autoPass: localStorage.getItem("tt-autopass") === "1",
    health: { ready: false },
    decks: [],
    screen: "boot",
    room: null,
    game: null,
    ws: null,
    zone: null,
    inspect: null,
    menu: false,
    logOpen: false,
    importResult: null,
    importError: "",
    decisionId: null,
    picks: [],
    amounts: {},
    inputText: "",
    lastAuto: "",
  };

  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  function toast(message) {
    toastEl.textContent = message;
    toastEl.classList.add("show");
    clearTimeout(toastEl._t);
    toastEl._t = setTimeout(() => toastEl.classList.remove("show"), 3200);
  }

  async function api(path, body) {
    const res = await fetch(path, body ? {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    } : undefined);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || "That didn't work.");
    return data;
  }

  function saveIdentity() {
    localStorage.setItem("tt-name", ui.name);
    localStorage.setItem("tt-format", ui.format);
    localStorage.setItem("tt-deck", ui.deckId);
    localStorage.setItem("tt-autopass", ui.autoPass ? "1" : "0");
  }

  function rememberRoom(room) {
    ui.room = room;
    if (room && room.code && room.token) {
      sessionStorage.setItem("tt-room", room.code);
      sessionStorage.setItem("tt-token", room.token);
    }
  }

  function filteredDecks() {
    const q = ui.deckQuery.trim().toLowerCase();
    return ui.decks.filter((deck) => {
      if (!q) return true;
      return (deck.name || "").toLowerCase().includes(q)
        || (deck.commander || "").toLowerCase().includes(q);
    }).slice(0, 40);
  }

  function imgUrl(name) {
    if (!name) return "";
    return "/api/cards/image?name=" + encodeURIComponent(name);
  }

  function show(screen) {
    ui.screen = screen;
    if (screen !== "table") closeSocket();
    if (screen === "home") renderHome();
    else if (screen === "import") renderImport();
    else if (screen === "lobby") renderLobby();
    else if (screen === "table") renderTableShell();
  }

  function renderHome() {
    const decks = filteredDecks();
    const selected = ui.decks.find((d) => d.id === ui.deckId);
    app.innerHTML = `
      <main class="screen">
        <header class="brand">
          <p class="eyebrow">Magic on your phone</p>
          <h1>Tabletop</h1>
          <p class="lede">The rules engine handles priority, the stack, combat, and triggers. You play the cards.</p>
        </header>
        <section class="panel">
          <label class="field">Your name
            <input id="name" type="text" maxlength="24" value="${esc(ui.name)}" placeholder="What should the table call you?">
          </label>
          <div class="seg" role="group" aria-label="Format">
            <button type="button" data-format="duel" class="${ui.format === "duel" ? "on" : ""}">Duel · 20 life</button>
            <button type="button" data-format="commander" class="${ui.format === "commander" ? "on" : ""}">Commander · 40 life</button>
          </div>
        </section>
        <section class="panel">
          <label class="field">Deck
            <input id="deck-query" type="search" placeholder="Search precons or imports" value="${esc(ui.deckQuery)}">
          </label>
          <div class="decks" id="deck-list">
            <button type="button" class="deck ${ui.deckId ? "" : "on"}" data-deck="">
              Surprise me
              <small>A built-in ${ui.format === "commander" ? "commander precon" : "duel deck"}</small>
            </button>
            ${decks.map((deck) => `
              <button type="button" class="deck ${deck.id === ui.deckId ? "on" : ""}" data-deck="${esc(deck.id)}">
                ${esc(deck.name)}
                <small>${deck.commander ? esc(deck.commander) + " · " : ""}${deck.source === "import" ? "Imported" : "Built in"}${deck.unsupportedCount ? " · " + deck.unsupportedCount + " unsupported" : ""}</small>
              </button>`).join("")}
          </div>
          <p class="hint">${selected ? "Playing " + esc(selected.name) + "." : "Leave this on Surprise me and the table picks a deck."} ${ui.decks.length > 40 ? ui.decks.length + " decks — search to narrow them." : ""}</p>
        </section>
        <button class="btn" id="play-ai" type="button">${ui.format === "commander" ? "Commander with 3 AI" : "Duel the AI"}</button>
        <button class="btn-ghost" id="play-other" type="button">${ui.format === "commander" ? "Duel the AI instead" : "Commander with 3 AI"}</button>
        <div class="row">
          <button class="btn-ghost" id="host" type="button">Host a table</button>
          <button class="btn-ghost" id="join-open" type="button">Join with a code</button>
        </div>
        <button class="btn-ghost" id="import" type="button">Import from Moxfield</button>
        <p class="hint">Card art is loaded from Scryfall when you play. Nothing is stored in the app.</p>
        <form id="join" class="panel" hidden>
          <label class="field">Invite code
            <input id="join-code" type="text" maxlength="8" autocapitalize="characters" placeholder="ABCD">
          </label>
          <button class="btn" type="submit">Sit down</button>
        </form>
      </main>`;
    bindHome();
  }

  function bindHome() {
    const name = document.getElementById("name");
    name.addEventListener("input", () => { ui.name = name.value; saveIdentity(); });
    app.querySelectorAll("[data-format]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        ui.format = btn.dataset.format;
        ui.deckId = "";
        saveIdentity();
        await loadDecks();
        renderHome();
      });
    });
    const query = document.getElementById("deck-query");
    query.addEventListener("input", () => { ui.deckQuery = query.value; renderHome(); query.focus(); });
    app.querySelectorAll("[data-deck]").forEach((btn) => {
      btn.addEventListener("click", () => {
        ui.deckId = btn.dataset.deck;
        saveIdentity();
        renderHome();
      });
    });
    document.getElementById("play-ai").addEventListener("click", () => quick(ui.format, ui.deckId));
    document.getElementById("play-other").addEventListener("click", () => quick(ui.format === "commander" ? "duel" : "commander", ""));
    document.getElementById("host").addEventListener("click", host);
    document.getElementById("import").addEventListener("click", () => show("import"));
    document.getElementById("join-open").addEventListener("click", () => {
      document.getElementById("join").hidden = false;
      document.getElementById("join-code").focus();
    });
    document.getElementById("join").addEventListener("submit", (event) => {
      event.preventDefault();
      join(document.getElementById("join-code").value);
    });
  }

  async function loadDecks() {
    try {
      const data = await api("/api/decks?format=" + encodeURIComponent(ui.format));
      ui.decks = data.decks || [];
    } catch (err) {
      toast(err.message);
    }
  }

  async function quick(format, deckId) {
    ui.format = format;
    if (deckId) ui.deckId = deckId;
    saveIdentity();
    try {
      const room = await api("/api/rooms/quick", {
        name: ui.name || "You",
        format,
        deckId: deckId || "",
        autopilot: false,
      });
      rememberRoom(room);
      toast("Game started");
      show("table");
      connect();
    } catch (err) {
      toast(err.message);
    }
  }

  async function host() {
    try {
      const room = await api("/api/rooms", {
        name: ui.name || "You",
        format: ui.format,
        deckId: ui.deckId || "",
      });
      rememberRoom(room);
      show("lobby");
    } catch (err) {
      toast(err.message);
    }
  }

  async function join(code) {
    try {
      const room = await api("/api/rooms/join", { code: code.trim(), name: ui.name || "Friend" });
      rememberRoom(room);
      show("lobby");
      pollLobby();
    } catch (err) {
      toast(err.message);
    }
  }

  function renderImport() {
    const result = ui.importResult;
    app.innerHTML = `
      <main class="screen">
        <header class="brand">
          <p class="eyebrow">Deck import</p>
          <h1>Moxfield</h1>
          <p class="lede">Paste a public deck link, or the exported list if the link is blocked.</p>
        </header>
        <form id="import-form" class="panel">
          <label class="field">Moxfield URL
            <input id="imp-url" type="text" placeholder="https://moxfield.com/decks/…">
          </label>
          <label class="field">Or a deck list
            <textarea id="imp-text" placeholder="Commander&#10;1 Atraxa, Praetors' Voice&#10;&#10;Deck&#10;1 Sol Ring"></textarea>
          </label>
          <label class="field">Name (optional)
            <input id="imp-name" type="text" placeholder="My deck">
          </label>
          <div class="seg">
            <button type="button" data-imp-format="duel" class="${ui.format === "duel" ? "on" : ""}">Duel</button>
            <button type="button" data-imp-format="commander" class="${ui.format === "commander" ? "on" : ""}">Commander</button>
          </div>
          <button class="btn" type="submit">Map cards</button>
          ${ui.importError ? `<p class="error">${esc(ui.importError)}</p>` : ""}
        </form>
        ${result ? importSummary(result) : ""}
        <button class="btn-ghost" id="back" type="button">Back</button>
      </main>`;
    app.querySelectorAll("[data-imp-format]").forEach((btn) => {
      btn.addEventListener("click", () => { ui.format = btn.dataset.impFormat; saveIdentity(); renderImport(); });
    });
    document.getElementById("import-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      ui.importError = "";
      try {
        ui.importResult = await api("/api/decks/import", {
          url: document.getElementById("imp-url").value.trim(),
          text: document.getElementById("imp-text").value,
          name: document.getElementById("imp-name").value,
          format: ui.format,
        });
        ui.deckId = ui.importResult.id;
        ui.format = ui.importResult.format || ui.format;
        saveIdentity();
        await loadDecks();
        renderImport();
      } catch (err) {
        ui.importError = err.message;
        renderImport();
      }
    });
    document.getElementById("back").addEventListener("click", () => show("home"));
    const use = document.getElementById("use-deck");
    if (use) use.addEventListener("click", () => show("home"));
  }

  function importSummary(deck) {
    const missing = deck.unsupported || [];
    return `
      <section class="panel">
        <h2>${esc(deck.name)}</h2>
        <p>${deck.commander ? "Commander: <strong>" + esc(deck.commander) + "</strong>. " : ""}${deck.mainCount || 0} cards in the deck.</p>
        ${missing.length
          ? `<p class="error">${missing.length} card${missing.length === 1 ? "" : "s"} ${missing.length === 1 ? "is" : "are"} not in Forge and will be left out:</p><ul class="list">${missing.map((name) => `<li>${esc(name)}</li>`).join("")}</ul>`
          : `<p class="ok">Every card mapped onto Forge.</p>`}
        <button class="btn" id="use-deck" type="button">Use this deck</button>
      </section>`;
  }

  function renderLobby() {
    const room = ui.room;
    if (!room) { show("home"); return; }
    app.innerHTML = `
      <main class="screen">
        <header class="brand">
          <p class="eyebrow">${room.format === "commander" ? "Commander table" : "Duel table"}</p>
          <div class="code">${esc(room.code)}</div>
          <p class="lede">Send that code to a friend. Empty seats can be a person or the AI.</p>
        </header>
        <section class="panel" id="seats">
          ${(room.seats || []).map((seat) => seatRow(seat, room)).join("")}
        </section>
        ${room.error ? `<p class="error">${esc(room.error)}</p>` : ""}
        ${room.host ? `<button class="btn" id="start" type="button">Start the game</button>` : `<p class="hint">Waiting for the host to start.</p>`}
        <button class="btn-ghost" id="leave" type="button">Leave the table</button>
      </main>`;
    document.getElementById("leave").addEventListener("click", () => {
      sessionStorage.removeItem("tt-room");
      ui.room = null;
      clearInterval(lobbyTimer);
      show("home");
    });
    pollLobby();
    const start = document.getElementById("start");
    if (start) start.addEventListener("click", startGame);
    app.querySelectorAll("[data-seat-kind]").forEach((btn) => {
      btn.addEventListener("click", () => updateSeat(Number(btn.dataset.seatKind), { kind: btn.dataset.kind }));
    });
    app.querySelectorAll("[data-seat-deck]").forEach((sel) => {
      sel.addEventListener("change", () => updateSeat(Number(sel.dataset.seatDeck), { deckId: sel.value }));
    });
  }

  function seatRow(seat, room) {
    const label = seat.kind === "open" ? "Open seat" : seat.kind === "ai" ? seat.name + " · AI" : (seat.name || "Player") + (seat.yours ? " · you" : "");
    const decks = ui.decks.filter((d) => d.format === room.format);
    const known = new Map(decks.map((d) => [d.id, d.name]));
    const shown = decks.slice(0, 60);
    if (seat.deckId && !shown.some((d) => d.id === seat.deckId)) {
      shown.unshift({ id: seat.deckId, name: known.get(seat.deckId) || "Chosen deck" });
    }
    const options = `<option value="">Surprise deck</option>` + shown.map((d) =>
      `<option value="${esc(d.id)}" ${d.id === seat.deckId ? "selected" : ""}>${esc(d.name)}</option>`).join("");
    const controls = room.host ? `
      <div>
        ${seat.index === 0 ? "" : `
          <button type="button" class="btn-ghost" data-seat-kind="${seat.index}" data-kind="ai">AI</button>
          <button type="button" class="btn-ghost" data-seat-kind="${seat.index}" data-kind="open">Open</button>`}
      </div>` : "";
    return `
      <div class="seat">
        <div>
          <strong>Seat ${seat.index + 1}</strong>
          <div class="hint">${esc(label)}</div>
          ${(room.host || seat.yours) && seat.kind !== "open" ? `<select data-seat-deck="${seat.index}">${options}</select>` : ""}
        </div>
        ${controls}
      </div>`;
  }

  async function updateSeat(index, patch) {
    try {
      const room = await api("/api/rooms/seat", {
        code: ui.room.code,
        token: ui.room.token,
        index,
        ...patch,
      });
      room.token = ui.room.token;
      rememberRoom(room);
      renderLobby();
    } catch (err) {
      toast(err.message);
    }
  }

  async function startGame() {
    try {
      const room = await api("/api/rooms/start", { code: ui.room.code, token: ui.room.token });
      rememberRoom(room);
      show("table");
      connect();
    } catch (err) {
      toast(err.message);
      if (ui.room) ui.room.error = err.message;
      renderLobby();
    }
  }

  let lobbyTimer = 0;
  function pollLobby() {
    clearInterval(lobbyTimer);
    lobbyTimer = setInterval(async () => {
      if (ui.screen !== "lobby" || !ui.room) { clearInterval(lobbyTimer); return; }
      try {
        const room = await api("/api/rooms/status?code=" + encodeURIComponent(ui.room.code) + "&token=" + encodeURIComponent(ui.room.token));
        room.token = ui.room.token;
        const changed = JSON.stringify(room.seats) !== JSON.stringify(ui.room.seats) || room.status !== ui.room.status;
        ui.room = room;
        if (room.status === "playing") {
          clearInterval(lobbyTimer);
          show("table");
          connect();
          return;
        }
        if (changed) renderLobby();
      } catch (err) {
        toast(err.message);
      }
    }, 1500);
  }

  function renderTableShell() {
    clearInterval(lobbyTimer);
    app.innerHTML = `
      <main class="table-screen">
        <header class="bar">
          <div class="phase" id="phase"><strong>Shuffling</strong><span>The table is waking up</span></div>
          <button class="icon-btn" id="menu-btn" type="button" aria-label="Menu">⋯</button>
        </header>
        <div class="felt" id="felt"></div>
        <aside class="side">
          <div class="hand" id="hand"></div>
          <div class="dock" id="dock"></div>
        </aside>
      </main>
      <div id="sheets"></div>`;
    document.getElementById("menu-btn").addEventListener("click", () => { ui.menu = !ui.menu; paintSheets(); });
    document.getElementById("felt").addEventListener("click", onFeltClick);
    document.getElementById("hand").addEventListener("click", onFeltClick);
    paintTable();
  }

  function stateBody() {
    return ui.game && ui.game.state ? ui.game.state : null;
  }

  function youPlayer(s) {
    const id = ui.game && ui.game.you != null ? ui.game.you : s.you;
    return (s.players || []).find((p) => p.id === id) || null;
  }

  function paintTable() {
    if (ui.screen !== "table") return;
    const s = stateBody();
    const phase = document.getElementById("phase");
    const felt = document.getElementById("felt");
    const hand = document.getElementById("hand");
    const dock = document.getElementById("dock");
    if (!phase || !s) return;
    const you = youPlayer(s);
    const others = (s.players || []).filter((p) => !you || p.id !== you.id);
    const turnName = (s.players || []).find((p) => p.id === s.turnPlayer);
    phase.innerHTML = `<strong>${esc(s.phaseLabel || s.phase || "Starting")}</strong><span>Turn ${s.turn || 1}${turnName ? " · " + esc(turnName.name) : ""}</span>`;
    felt.innerHTML = `
      ${others.map((p) => playerBlock(p, s, false)).join("")}
      ${stackHtml(s)}
      ${combatHtml(s)}
      ${you ? playerBlock(you, s, true) : `<p class="hint">Waiting for the opening hand.</p>`}`;
    syncHand(hand, you);
    paintDock(dock, s, you);
    paintSheets();
    maybeAutoPass(s);
  }

  function playerBlock(player, s, mine) {
    const prompt = s.prompt || {};
    const pickable = (prompt.players || []).includes(player.id);
    const dmg = (player.commanderDamage || []).map((row) => `${esc(row.from)} ${row.amount}`).join(" · ");
    return `
      <section class="player">
        <button type="button" class="plaque ${pickable ? "pickable" : ""}" data-player="${player.id}">
          <span class="who">${esc(player.name)}${player.ai ? " · AI" : ""}${player.lost ? " · out" : ""}</span>
          <span class="meta">${player.library || 0} library · ${player.graveyard || 0} gy · ${player.hand || 0} hand${player.poison ? " · poison " + player.poison : ""}</span>
          <span class="life">${player.life}</span>
        </button>
        ${dmg ? `<p class="cmd">Commander damage: ${dmg}</p>` : ""}
        ${manaHtml(player.mana)}
        ${zoneLane(player.command, "command")}
        <div class="lane ${mine ? "you-lane" : ""}" data-zone="board">${(player.battlefield || []).map((card) => cardHtml(card, s)).join("")}</div>
      </section>`;
  }

  function manaHtml(mana) {
    if (!mana) return "";
    const bits = ["W", "U", "B", "R", "G", "C"].filter((k) => mana[k] > 0)
      .map((k) => `<b class="pip pip-${k}">${mana[k]}</b>`).join("");
    return bits ? `<div class="pips">${bits}</div>` : "";
  }

  function zoneLane(cards, kind) {
    if (!cards || !cards.length) return "";
    return `<div class="lane">${cards.map((card) => cardHtml(card, stateBody(), kind)).join("")}</div>`;
  }

  function cardHtml(card, s, kind) {
    const prompt = (s && s.prompt) || {};
    const can = (prompt.selectable || []).includes(card.id) || (prompt.weak || []).includes(card.id);
    const picked = (prompt.selected || []).includes(card.id);
    const classes = ["card", card.tapped ? "tapped" : "", can ? "can" : "", picked ? "picked" : "", card.attacking ? "attacking" : "", card.hidden ? "back" : ""].filter(Boolean).join(" ");
    const src = card.hidden ? "" : imgUrl(card.imageName || card.name);
    const pt = card.creature ? `${card.power}/${card.toughness}` : (card.loyalty != null ? String(card.loyalty) : "");
    return `<button type="button" class="${classes}" data-card="${card.id}" data-name="${esc(card.imageName || card.name || "")}">
      ${src ? `<img alt="" src="${esc(src)}" data-fallback="${esc(card.name)}">` : ""}
      <span class="name">${esc(card.name || "Card")}</span>
      ${pt ? `<span class="pt">${esc(pt)}</span>` : ""}
      ${card.commander || kind === "command" ? `<span class="star">C</span>` : ""}
    </button>`;
  }

  function stackHtml(s) {
    const stack = s.stack || [];
    if (!stack.length) return "";
    return `<div class="stack">${stack.map((item, index) => `
      <div class="stack-item">
        ${item.imageName || item.name ? `<img alt="" src="${esc(imgUrl(item.imageName || item.name))}">` : ""}
        <div><strong>${index === 0 ? "Top · " : ""}${esc(item.name || "Spell")}</strong><p>${esc(item.text || "")}</p></div>
      </div>`).join("")}</div>`;
  }

  function combatHtml(s) {
    const attackers = s.combat && s.combat.attackers || [];
    if (!attackers.length) return "";
    return `<div class="combat">${attackers.map((a) => `${esc(a.name)} → ${esc(a.defender || "a defender")}${(a.blockers || []).length ? " (blocked by " + a.blockers.map((b) => esc(b.name)).join(", ") + ")" : ""}`).join("<br>")}</div>`;
  }

  function syncHand(container, you) {
    if (!container) return;
    const cards = you && you.handCards || [];
    const s = stateBody();
    container.innerHTML = cards.map((card, index) => cardHtml(card, s)).join("");
    const mid = (cards.length - 1) / 2;
    [...container.children].forEach((el, index) => {
      const rot = (index - mid) * 4;
      const lift = Math.abs(index - mid) * 3;
      el.style.transform = `rotate(${rot}deg) translateY(${lift}px)`;
      el.style.zIndex = String(index);
    });
  }

  function paintDock(dock, s, you) {
    const prompt = s.prompt || {};
    const attackers = s.phase === "COMBAT_DECLARE_ATTACKERS" && you && s.turnPlayer === you.id;
    dock.innerHTML = `
      <p class="prompt">${esc(prompt.message || (s.gameOver ? "Game over" : "Waiting…"))}</p>
      <div class="row">
        <button class="btn-ghost" id="act-cancel" type="button" ${prompt.cancelEnabled ? "" : "disabled"}>${esc(prompt.cancel || "Cancel")}</button>
        <button class="btn" id="act-ok" type="button" ${prompt.okEnabled ? "" : "disabled"}>${esc(prompt.ok || "OK")}</button>
      </div>
      ${attackers ? `<button class="btn-ghost" id="act-alpha" type="button">Attack with everything</button>` : ""}
      <div class="row">
        <button class="btn-ghost" id="open-gy" type="button">Graveyard</button>
        <button class="btn-ghost" id="open-log" type="button">Log</button>
      </div>`;
    document.getElementById("act-ok").addEventListener("click", () => send({ action: "ok" }));
    document.getElementById("act-cancel").addEventListener("click", () => send({ action: "cancel" }));
    const alpha = document.getElementById("act-alpha");
    if (alpha) alpha.addEventListener("click", () => send({ action: "alpha" }));
    document.getElementById("open-gy").addEventListener("click", () => { ui.zone = "graveyard"; paintSheets(); });
    document.getElementById("open-log").addEventListener("click", () => { ui.logOpen = true; paintSheets(); });
  }

  function onFeltClick(event) {
    const img = event.target.closest("img[data-fallback]");
    if (img && event.type === "error") return;
    const cardBtn = event.target.closest("[data-card]");
    if (cardBtn) {
      const id = Number(cardBtn.dataset.card);
      const s = stateBody();
      const prompt = (s && s.prompt) || {};
      const can = (prompt.selectable || []).includes(id) || (prompt.weak || []).includes(id);
      if (can) send({ action: "card", cardId: id });
      else ui.inspect = findCard(id);
      paintSheets();
      return;
    }
    const playerBtn = event.target.closest("[data-player]");
    if (playerBtn && playerBtn.classList.contains("pickable")) {
      send({ action: "player", playerId: Number(playerBtn.dataset.player) });
    }
  }

  function findCard(id) {
    const s = stateBody();
    if (!s) return null;
    for (const player of s.players || []) {
      for (const zone of ["battlefield", "handCards", "graveyardCards", "exileCards", "command"]) {
        const hit = (player[zone] || []).find((card) => card.id === id);
        if (hit) return hit;
      }
    }
    return null;
  }

  function paintSheets() {
    const root = document.getElementById("sheets");
    if (!root) return;
    const s = stateBody();
    const decision = s && s.decision;
    if (decision && decision.id !== ui.decisionId) {
      ui.decisionId = decision.id;
      ui.picks = [];
      ui.amounts = {};
      ui.inputText = decision.initial || "";
    }
    if (!decision) ui.decisionId = null;
    let html = "";
    if (decision) html += decisionSheet(decision);
    if (ui.inspect) html += inspectSheet(ui.inspect);
    if (ui.zone) html += zoneSheet();
    if (ui.logOpen) html += logSheet();
    if (ui.menu) html += menuSheet();
    if (s && s.gameOver) html += gameOverSheet(s);
    root.innerHTML = html;
    bindSheets();
  }

  function decisionSheet(d) {
    const options = d.options || [];
    let body = "";
    if (d.kind === "input") {
      body = `<label class="field">${esc(d.message || "")}
        <input id="dec-input" type="${d.numeric ? "number" : "text"}" value="${esc(ui.inputText)}">
      </label><button class="btn" id="dec-send" type="button">Confirm</button>`;
    } else if (d.kind === "assign") {
      body = options.map((opt) => {
        const n = ui.amounts[opt.index] || 0;
        return `<div class="assign"><button type="button" data-amt="${opt.index}" data-dir="-1">−</button><span>${n}</span><button type="button" data-amt="${opt.index}" data-dir="1">+</button><span>${esc(opt.label)}</span></div>`;
      }).join("") + `<p class="hint">Assign ${d.amount}. Currently ${Object.values(ui.amounts).reduce((a, b) => a + b, 0)}.</p><button class="btn" id="dec-send" type="button">Confirm</button>`;
    } else if (d.kind === "order") {
      body = `<p class="hint">Tap cards in the order you want. First tap is the top.</p>`
        + options.map((opt) => `<button type="button" class="option ${ui.picks.includes(opt.index) ? "on" : ""}" data-opt="${opt.index}">${ui.picks.indexOf(opt.index) >= 0 ? (ui.picks.indexOf(opt.index) + 1) + ". " : ""}${esc(opt.label)}</button>`).join("")
        + `<button class="btn" id="dec-send" type="button">Confirm order</button>`;
    } else if (d.kind === "reveal") {
      body = options.map((opt) => `<div class="option">${optionFace(opt)}</div>`).join("")
        + `<button class="btn" id="dec-send" type="button">Continue</button>`;
    } else {
      const multi = (d.max || 1) > 1 || (d.min === 0 && (d.max || 0) !== 1);
      body = options.map((opt) => `<button type="button" class="option ${ui.picks.includes(opt.index) ? "on" : ""}" data-opt="${opt.index}">${optionFace(opt)}</button>`).join("")
        + (multi || d.min === 0 ? `<button class="btn" id="dec-send" type="button">Confirm</button>` : "");
    }
    return `<div class="sheet"><article><h2>${esc(d.title || "Choose")}</h2><p class="hint">${esc(d.message || "")}</p>${body}</article></div>`;
  }

  function optionFace(opt) {
    const name = opt.name || "";
    const img = name ? `<img alt="" src="${esc(imgUrl(name))}">` : "";
    return `${img}${esc(opt.label || name || "Option")}`;
  }

  function inspectSheet(card) {
    const src = card.hidden ? "" : imgUrl(card.imageName || card.name);
    return `<div class="overlay" data-close="inspect"><article>
      ${src ? `<img class="inspect-card" alt="${esc(card.name)}" src="${esc(src)}">` : `<div class="card back" style="width:180px;height:250px;margin:0 auto 10px"></div>`}
      <h2>${esc(card.name)}</h2>
      <p class="hint">${esc(card.type || "")} ${card.cost ? "· " + esc(card.cost) : ""}</p>
      <button class="btn" type="button" data-close="inspect">Close</button>
    </article></div>`;
  }

  function zoneSheet() {
    const s = stateBody();
    const you = s && youPlayer(s);
    const cards = you ? (ui.zone === "exile" ? you.exileCards : you.graveyardCards) || [] : [];
    return `<div class="sheet"><article>
      <h2>${ui.zone === "exile" ? "Exile" : "Graveyard"}</h2>
      <div class="lane">${cards.map((card) => cardHtml(card, s)).join("") || `<p class="hint">Empty.</p>`}</div>
      <button class="btn-ghost" type="button" data-zone-switch="exile">Exile</button>
      <button class="btn" type="button" data-close="zone">Close</button>
    </article></div>`;
  }

  function logSheet() {
    const lines = (stateBody() && stateBody().log) || [];
    return `<div class="sheet"><article><h2>Game log</h2>${lines.map((line) => `<p class="log-line">${esc(line)}</p>`).join("") || `<p class="hint">Nothing yet.</p>`}<button class="btn" type="button" data-close="log">Close</button></article></div>`;
  }

  function menuSheet() {
    return `<div class="sheet"><article class="menu"><h2>Table</h2>
      <label class="check"><input id="autopass" type="checkbox" ${ui.autoPass ? "checked" : ""}> Auto-pass priority</label>
      <button class="btn-danger" id="concede" type="button">Concede</button>
      <button class="btn" type="button" data-close="menu">Close</button>
    </article></div>`;
  }

  function gameOverSheet(s) {
    const title = s.draw ? "Draw" : (s.winner ? s.winner + " wins" : "Game over");
    return `<div class="overlay"><article><h2>${esc(title)}</h2><button class="btn" id="leave-game" type="button">Back to the lobby</button></article></div>`;
  }

  function bindSheets() {
    const root = document.getElementById("sheets");
    if (!root) return;
    root.querySelectorAll("[data-close]").forEach((el) => el.addEventListener("click", (event) => {
      if (event.currentTarget !== event.target && event.currentTarget.tagName !== "BUTTON") return;
      const which = el.dataset.close;
      if (which === "inspect") ui.inspect = null;
      if (which === "zone") ui.zone = null;
      if (which === "log") ui.logOpen = false;
      if (which === "menu") ui.menu = false;
      paintSheets();
    }));
    root.querySelectorAll("[data-opt]").forEach((btn) => btn.addEventListener("click", () => chooseOption(Number(btn.dataset.opt))));
    root.querySelectorAll("[data-amt]").forEach((btn) => btn.addEventListener("click", () => {
      const key = btn.dataset.amt;
      const dir = Number(btn.dataset.dir);
      ui.amounts[key] = Math.max(0, (ui.amounts[key] || 0) + dir);
      paintSheets();
    }));
    const sendBtn = document.getElementById("dec-send");
    if (sendBtn) sendBtn.addEventListener("click", submitDecision);
    const input = document.getElementById("dec-input");
    if (input) input.addEventListener("input", () => { ui.inputText = input.value; });
    const auto = document.getElementById("autopass");
    if (auto) auto.addEventListener("change", () => { ui.autoPass = auto.checked; saveIdentity(); });
    const concede = document.getElementById("concede");
    if (concede) concede.addEventListener("click", () => { send({ action: "concede" }); ui.menu = false; });
    const leave = document.getElementById("leave-game");
    if (leave) leave.addEventListener("click", () => { sessionStorage.removeItem("tt-room"); show("home"); });
    const exile = root.querySelector("[data-zone-switch]");
    if (exile) exile.addEventListener("click", () => { ui.zone = ui.zone === "exile" ? "graveyard" : "exile"; paintSheets(); });
    root.querySelectorAll("[data-card]").forEach((btn) => btn.addEventListener("click", () => {
      ui.inspect = findCard(Number(btn.dataset.card));
      paintSheets();
    }));
    root.querySelectorAll("img").forEach((img) => {
      img.addEventListener("error", () => { img.style.display = "none"; });
    });
  }

  function chooseOption(index) {
    const d = stateBody() && stateBody().decision;
    if (!d) return;
    const multi = d.kind === "order" || (d.max || 1) > 1 || d.min === 0;
    if (!multi && d.kind !== "order") {
      ui.picks = [index];
      submitDecision();
      return;
    }
    const at = ui.picks.indexOf(index);
    if (at >= 0) ui.picks.splice(at, 1);
    else ui.picks.push(index);
    paintSheets();
  }

  function submitDecision() {
    const d = stateBody() && stateBody().decision;
    if (!d) return;
    send({
      type: "answer",
      id: d.id,
      indices: ui.picks,
      text: ui.inputText,
      amounts: ui.amounts,
    });
  }

  function maybeAutoPass(s) {
    if (!ui.autoPass || s.decision || s.gameOver) return;
    const prompt = s.prompt || {};
    const selectable = (prompt.selectable || []).length + (prompt.players || []).length;
    const message = prompt.message || "";
    if (!prompt.okEnabled || selectable > 0) return;
    if (!/pass priority/i.test(message)) return;
    const sig = message + "|" + (s.phase || "") + "|" + (s.priorityPlayer || "");
    if (sig === ui.lastAuto) return;
    ui.lastAuto = sig;
    setTimeout(() => send({ action: "ok" }), 350);
  }

  function send(message) {
    if (!ui.ws || ui.ws.readyState !== WebSocket.OPEN) {
      toast("Reconnecting to the table…");
      return;
    }
    ui.ws.send(JSON.stringify(message));
  }

  function closeSocket() {
    if (ui.ws) {
      ui.ws.onclose = null;
      ui.ws.close();
      ui.ws = null;
    }
  }

  function connect() {
    closeSocket();
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url = `${proto}://${location.host}/ws/seat?room=${encodeURIComponent(ui.room.code)}&token=${encodeURIComponent(ui.room.token)}`;
    const ws = new WebSocket(url);
    ui.ws = ws;
    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === "state") {
          ui.game = msg;
          if (ui.screen === "table") paintTable();
        }
      } catch (err) {
        console.warn(err);
      }
    };
    ws.onclose = () => {
      if (ui.screen === "table" && ui.ws === ws) {
        setTimeout(connect, 1200);
      }
    };
  }

  async function boot() {
    app.innerHTML = `<div class="boot"><div><div class="ring"></div><h1>Tabletop</h1><p class="lede" id="boot-msg">Loading the card database…</p></div></div>`;
    const tick = async () => {
      try {
        const health = await fetch("/api/health").then((res) => res.json());
        ui.health = health;
        if (health.error) {
          document.getElementById("boot-msg").textContent = health.error;
          return;
        }
        if (!health.ready) {
          setTimeout(tick, 1000);
          return;
        }
        await loadDecks();
        const code = sessionStorage.getItem("tt-room");
        const token = sessionStorage.getItem("tt-token");
        if (code && token) {
          try {
            const room = await api("/api/rooms/status?code=" + encodeURIComponent(code) + "&token=" + encodeURIComponent(token));
            room.token = token;
            rememberRoom(room);
            if (room.status === "playing") { show("table"); connect(); return; }
            if (room.status === "lobby") { show("lobby"); pollLobby(); return; }
          } catch (err) {
            sessionStorage.removeItem("tt-room");
          }
        }
        show("home");
      } catch (err) {
        const msg = document.getElementById("boot-msg");
        if (msg) msg.textContent = "Waiting for the server…";
        setTimeout(tick, 1000);
      }
    };
    tick();
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => {});
    }
  }

  document.addEventListener("error", (event) => {
    const img = event.target;
    if (img && img.tagName === "IMG") img.style.visibility = "hidden";
  }, true);

  boot();
})();
