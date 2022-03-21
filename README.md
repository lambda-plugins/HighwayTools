<img src="https://cdn.discordapp.com/attachments/669533180310716416/748810374748307476/output-onlinepngtools.png" width="100%"/>

### Description

Adding fully automated highway building as plugin to Lambda.
The tool places building material and destroys wrong blocks in high frequency using well-timed packets.
The module can build autonomously in all 8 directions while the pathfinder keeps the desired position and takes care of any possible dangerous situations like liquids or gaps.
Advanced inventory management allows the bot to fully utilize materials stocked in shulker boxes or in the ender chest.
It is highly customizable and allows deep changes in system using configurations.
It is fully compatible with most modules of Lambda like `LagNotifier`, `AutoEat` etc and also is confirmed to work with `Rusherhack`, `Future`, `Impact` etc.

### Features
- [x] Digs tunnel and paves Obsidian floor at the same time
- [x] Faster block breaking and placing then any other solution
    - Reaches `20+ blocks per second` mining interactions on 2b2t
    - Reaches `7+ blocks per second` placing interactions on 2b2t
    - Confirmed over `300%` faster than previous cutting edge digging solutions (source: MEG)
- [x] Intelligent liquid handling
    - Reacts on liquid pockets using cutting edge placing exploits to patch lava pockets before even opening them
    - Reacts on complex flow structures and cleans up
- [x] Long term inventory management
    - Dynamic material restock from shulker boxes and ender chests
    - Built in native AutoObsidian. Enable option Storage Management > Grind Obsidian
    - Saves minimum requirements of materials
    - No mouse grab on container open
- [x] Diagonal highway mode
- [x] Intelligent repair mode
- [x] The built-in Anti-Anti-Cheat works with 2b2t's anti-cheat and `NoCheatPlus`
- [x] Pauses on lag to avoid kick (enable `LagNotifier` for this feature)
- [x] Ignore Blocks: `Signs, Portals, Banners, Bedrock` and more
- [x] Choose custom-building materials
- [x] Auto clipping to starting coordinates
- [x] Commands:
    - `;highwaytools` - alias: `;ht`
    - `;ht ignore add <block>` Adds block to ignore list
    - `;ht ignore del <block>` Removes block from ignore list
    - `;ht material <block>` Choose an alternative building block (default: Obsidian)
    - `;ht filler <block>` Choose an alternative filler block to fill liquids (default: Netherrack)
    - `;ht settings` or `;ht` Shows detailed settings of the module
    - `;ht distance 500` for running the bot for a limited distance. (e.g. 500 blocks)
    - `;ht stash <Pos x> <Pos y> <Pos z>` or `;ht s <Pos x> <Pos y> <Pos z>` for automatically going to your stash to restock
- [x] Compatible with:
    - `LagNotifier (Baritone mode)` To stop while lagging to not get kicked
    - `AutoObsidian` to automatically get new Obsidian from Ender Chests even from shulker boxes
    - `AutoEat` set `PauseBaritone` on false and below health 19.0, and you're safe from lava and other threads having gapples in inventory
    - `AutoLog` to logout on any given danger
    - `AutoReconnect (Support pending)` to get back on server after waiting (for example a player comes in range)
    - `AntiHunger` slows food level decrease but makes block breaking slower
- [x] Highly dynamical generated blueprints
    - Three Modes: `Highway` (for full highways), `Tunnel` (optimized for digging), `Flat` (for repair obsidian sky)
    - `ClearSpace` Choose to break wrong blocks and tunneling
    - `ClearHeight` Choose the height of tunnel
    - `BuildWidth`  Choose the width of the highway
    - `Railing` Choose if the highway has rims/guardrails
    - `RailingHeight` Choose height of the rims/guardrails
    - `CornerBlock` Choose if u want to have a corner block or not

<img src="https://cdn.discordapp.com/attachments/744735300554588320/748222025109209097/photo_2020-08-26_18-39-26.jpg" width="20%"/>
<img src="https://cdn.discordapp.com/attachments/744735300554588320/748222044478374038/unknown.png" width="60%"/>

### Installation
1. Get the latest Lambda release here https://github.com/lambda-client/lambda/releases
2. Open Lambda menu in main menu to open plugin settings
3. Press `Open Plugin Folder`
4. Move plugin `HighwayTools-*.jar` into the folder `.minecraft/lambda/plugins`

### Known issues
- `AutoLog` is not compatible with `AutoReconnect`

### Troubleshooting
- Deactivate `AntiHunger` for faster block breaking because it makes you basically float.
- If stuck check, if AutoCenter is on `MOTION` to get moved to middle of the block (Baritone can't move exactly to block center)
- If placed block disappear increase the `TickDelayPlace` until it works
- Deactivate `IllegalPlacements` if the server requires (like 2b2t)
- Reset stash position if the bot keeps going to 0, 0

Any suggestions and questions: Constructor#9948 on Discord Made by @Avanatiker
Report bugs on [Issues](https://github.com/lambda-plugins/HighwayTools/issues) and if not possible message EnigmA_008#1505 on Discord.

`Copyright ©2019-2022` Constructor#9948 alias Avanatiker. All Rights Reserved. Permission to use, copy, modify, and distribute this software and its documentation for educational, research, and not-for-profit purposes, without fee and without a signed licensing agreement, is hereby granted, provided that the above copyright notice, this paragraph appears in all copies, modifications, and distributions.

By downloading this software you agree to be bound by the terms of service.


## Stars over time

[![Stargazers over time](https://starchart.cc/lambda-plugins/HighwayTools.svg)](https://starchart.cc/lambda-plugins/HighwayTools)