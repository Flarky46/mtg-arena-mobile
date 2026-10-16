# Tabletop

Magic on your phone. You tap the cards. [Forge](https://github.com/Card-Forge/forge) enforces the rules: priority, the stack, combat, triggers, state-based actions, and card effects. An AI opponent can sit across from you, or you can open a table and send a friend a four-letter code.

This is a small web app plus Forge. You do not install a rules engine yourself. Docker does that.

## Play it

You need [Docker](https://docs.docker.com/get-docker/) (Docker Desktop on a Mac or Windows PC is fine).

```bash
docker compose up
```

The first run builds Forge from source, so it takes a while and wants a few gigabytes of disk and about 3 GB of RAM. Later starts are ordinary.

When the log says `Listening on http://0.0.0.0:8080`:

1. On the same computer, open http://localhost:8080
2. On your phone, join the same Wi-Fi and open `http://` plus the computer's address, for example `http://192.168.1.20:8080`. The computer's firewall must allow port 8080.

The phone can use **Add to Home Screen**. The shell is cached. Card pictures are not stored in the app; they are loaded from Scryfall while you play.

### What you can do

- **Duel the AI.** 20 life, one opponent. Leave the deck on "Surprise me" and the AI brings a built-in deck, or pick one of the preconstructed decks.
- **Commander with 3 AI.** 40 life, free-for-all, you plus three AI players. The AI decks are Forge's commander precons.
- **Import a Moxfield deck.** Paste a public link such as `https://moxfield.com/decks/uf2fz274EUS6kSQrMRI3MQ`. The app reads Moxfield's public deck JSON, including the commander. If a card is not in Forge, the import says so by name and leaves that card out.
- If Moxfield's CDN refuses the server (this happens from some data centers), it says so. Open the deck on Moxfield, use Export, and paste the list. The same mapping applies.
- **Host a table.** You get a code like `FB3D`. A friend opens the app, taps **Join with a code**, and sits in an open seat. You can fill the other seats with AI. Duel is 2 seats. Commander is 4. Then tap **Start the game**.

Give yourself a name on the home screen so the table knows what to call you.

## Put it on the internet

The app is one container. A phone on cellular data needs a public address. A small virtual server is enough. Nothing here needs an API key.

You have to:

1. Create an account at a host. Any of these work:
   - A $5-a-month VPS (Hetzner, DigitalOcean, Linode, Vultr). Pick a machine with **at least 3 GB of RAM** and 15 GB of disk.
   - Oracle Cloud's always-free ARM VM, if you can get one. Use a shape with 2 GB or more and a bit of swap, because the build itself is hungry. Playing is lighter than building.
2. Install Docker and the Docker Compose plugin on that machine.
3. Copy this project onto it (`git clone` the repository).
4. Run `docker compose up -d`.
5. Open port **8080** in the host's firewall and in the server's firewall (`ufw allow 8080` on Ubuntu, or the cloud "security list" / "security group").
6. On the phone, open `http://YOUR-SERVER-IP:8080`.

Imported decks are saved in a Docker volume named `tabletop-data`. Tables themselves are forgotten when the container restarts. There are no accounts.

For a proper home-screen install over the internet, put HTTPS in front. [Caddy](https://caddyserver.com/) can do that with one block once you own a domain name:

```
tabletop.example.com {
    reverse_proxy localhost:8080
}
```

Point the domain at the server, install Caddy, and open ports 80 and 443. You do not change Tabletop for that.

## How the rules are enforced

Forge is the rules engine. This app does not reimplement Magic.

Forge was chosen over XMage and the other open engines because it has both of the things this app needs: the widest set of scripted cards (on the order of 35,000 names in the current card index) and a built-in AI (`PlayerControllerAi`) that can play a deck without a person attached. XMage also has wide coverage and a strong AI, but its server speaks JBoss Remoting rather than something a phone can join, and its card set is a little smaller. A Rust port of Forge (ManaBrew) exists and is still pre-release, so it is not used here.

The pinned Forge revision is commit `733a221e35613ec146ae4573df5406661c71c6f3`, version `2.0.16-SNAPSHOT`. Docker fetches that commit. The source is not copied into this repository.

## Licenses

Tabletop is GPL-3.0-or-later because it links Forge, which is GPL-3.0. See `LICENSE` and `NOTICE`.

Card images are fetched from Scryfall at runtime and are not part of this project. Forge's own download includes the card *scripts* the rules need. Those stay inside the engine image.

## Limits, honestly

- Odd prompts (ordering a library, splitting damage, "pick two") use a plain choice sheet. The common actions — play, tap, attack, block, pass priority, mulligan, stack — are on the table itself.
- The Forge AI plays legal Magic. It is not as sharp as Arena's AI on long combos.
- People sit before the AI, so in a mixed Commander game the humans take the earlier seats.
- Deck checks are relaxed so a casual import can be played. A commander deck still needs a commander.
- Rooms live in memory. Restarting the server ends games in progress. Imported decks survive in the data volume.
- Two games on one server share Forge's UI thread. A second table can wait while the first table is resolving a choice.
- Moxfield's public API is what the link import uses (`https://api.moxfield.com/v2/decks/all/{id}`). Private decks cannot be imported by URL.

## Next steps

- Sign-in, so a table survives a refresh on a new phone without the code being the only key.
- Store rooms somewhere other than memory.
- HTTPS in the compose file for people who already have a domain.
- A tighter pass-priority control, and AI personalities picked from Forge's profiles.
- Sideboard and deck-editor screens. The engine can already tell which cards it does not know.
