const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { randomUUID, createHash } = require('node:crypto')
const bedrock = require('bedrock-protocol')

const host = process.env.BEDROCK_HOST || '127.0.0.1'
const port = Number(process.env.BEDROCK_PORT || 19187)
const version = process.env.BEDROCK_VERSION || '1.26.51'
const clients = []
const checks = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function enumName(prefix) {
  const digest = createHash('sha256').update('ncf\0' + prefix.join('\0') + 'S').digest('hex').slice(0, 16)
  return `ncf_arg${prefix.length + 1}_${digest}`
}
function nativeValues(state, label, argument) {
  const definition = state.latest?.command_data.find(data => {
    const aliases = state.latest.enums[data.alias]
    return data.name === label || aliases && aliases.values.some(index => state.latest.enum_values[index] === label)
  })
  if (!definition) return []
  label = definition.name.toLowerCase()
  for (let i = 0; i < definition.overloads.length; i++) {
    const parameters = definition.overloads[i].parameters
    for (let j = 0; j < parameters.length; j++) {
      if (parameters[j].parameter_name !== argument || parameters[j].enum_type !== 'soft_enum') continue
      const prefix = ['java', String(i), String(j), argument]
      const digest = createHash('sha256').update(label + '\0' + prefix.join('\0') + 'S').digest('hex').slice(0, 16)
      return state.enums.get(`${label.replace(/[^a-zA-Z0-9_]/g, '_').slice(0, 24)}_arg5_${digest}`) || []
    }
  }
  return []
}
async function until(description, predicate, timeout = 60000, record = true) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    for (const state of clients) if (state.error) throw state.error
    if (predicate()) { if (record) { checks.push(description); console.log('PASS', description) }; return }
    await pause(100)
  }
  throw new Error('Timeout: ' + description)
}
function connect(username) {
  const state = { username, packets: [], enums: new Map(), availableCount: 0, spawned: false, runtimeId: 0n, position: { x: 0, y: 0, z: 0 } }
  clients.push(state)
  state.client = bedrock.createClient({ host, port, version, username, offline: true, transport: 'raknet', conLog: () => {} })
  // Offline profiles all default to XUID 0. Geyser keys sessions by XUID;
  // give the self-signed test identities distinct IDs before login signing.
  state.client.prependOnceListener('session', profile => { profile.xuid = username === 'NCTestA' ? '10001' : '10002' })
  state.client.on('error', error => { state.error = error })
  state.client.on('disconnect', packet => { if (!state.closing) state.error = new Error(JSON.stringify(packet)) })
  const respawn = () => state.client.queue('respawn', { position: state.position, state: 2, runtime_entity_id: state.runtimeId })
  state.client.on('start_game', packet => { state.runtimeId = packet.runtime_entity_id; state.position = packet.player_position })
  state.client.on('respawn', packet => {
    if (packet.state === 1) { state.position = packet.position; respawn() }
  })
  state.client.on('spawn', () => {
    // Complete the normal render-distance setup used by real clients.
    state.client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 })
    // Paper rejects commands from removed/dead entities. Previous test profiles
    // may be saved dead; ask for respawn after login (ignored for living players).
    respawn()
    state.spawned = true
  })
  state.client.on('available_commands', packet => {
    try {
      for (const definition of packet.command_data) {
        const aliases = packet.enums[definition.alias]
        const names = [definition.name, ...(aliases ? aliases.values.map(index => packet.enum_values[index]) : [])]
        assert(!names.includes('help'), 'Server /help must not return in any command packet')
      }
    } catch (error) { state.error = error }
    state.availableCount++
    state.definition = packet.command_data.find(command => command.name === 'ncf')
    state.latest = packet
    state.enums = new Map(packet.dynamic_enums.map(entry => [entry.name, entry.values]))
    state.packets.push({ type: 'available_commands', packet })
  })
  state.client.on('update_soft_enum', packet => {
    const old = state.enums.get(packet.enum_type) || []
    state.enums.set(packet.enum_type, packet.action_type === 'update' ? packet.options
      : packet.action_type === 'add' ? [...new Set([...old, ...packet.options])]
        : old.filter(value => !packet.options.includes(value)))
    state.packets.push({ type: 'update_soft_enum', packet })
  })
  state.client.on('text', packet => state.packets.push({ type: 'text', packet }))
  return state
}
async function command(state, text) {
  state.client.queue('command_request', {
    command: text, origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n },
    internal: false, version: ''
  })
}
const values = (state, prefix) => state.enums.get(enumName(prefix)) || []
async function prepare(state) {
  await until('Test profile receives command tree', () => state.spawned && values(state, []).join(',') === 'delete,set,list', 30000, false)
  // Previous interrupted runs may leave homes (including duplicates) in the
  // fixture's memory. Read the actual list and reset only this test identity.
  const mark = state.packets.length
  await command(state, '/ncf list')
  let response
  await until('Fixture responds after respawn', () => {
    response = state.packets.slice(mark).find(p => p.type === 'text' && /fixture:/.test(p.packet.message))
    return response
  }, 30000, false)
  const homes = response.packet.message.split('fixture:')[1].split(',').filter(Boolean)
  for (const home of homes) {
    assert(/^[A-Za-z0-9_]+$/.test(home), 'Unexpected test fixture home: ' + home)
    await command(state, '/ncf delete ' + home)
  }
  await command(state, '/ncf set ' + state.username + '_base')
  await until('Test profile has a clean home list', () => values(state, ['delete']).join(',') === state.username + '_base', 30000, false)
}
async function main() {
  assert(Number(process.versions.node.split('.')[0]) >= 24, 'Node.js 24+ is required')
  const a = connect('NCTestA')
  await prepare(a)
  await until('A receives native subcommands and own home', () => a.spawned && values(a, []).join(',') === 'delete,set,list' && values(a, ['delete']).includes('NCTestA_base'))
  assert.equal(a.definition.description, 'Integration fixture')
  assert.equal(a.definition.permission_level, 'any')
  assert(a.definition.overloads.some(o => o.parameters.length === 1 && o.parameters[0].parameter_name === 'args'))
  assert(a.definition.overloads.some(o => o.parameters.length === 2 && o.parameters[0].enum_type === 'enum' && o.parameters[1].enum_type === 'soft_enum'))
  checks.push('Description, permission, fallback and delete branch preserved')
  // Protocol 2193's bedrock-protocol schema names the suppress-autocompletion bit "unknown2".
  assert(a.definition.overloads.filter(o => o.parameters.length === 2 && o.parameters[0].enum_type === 'enum')
    .every(o => o.parameters[0].options.unknown2 === 1))
  checks.push('Generic literal branch avoids repeating its parent candidate')
  if (process.env.BEDROCK_NATIVE_ARGUMENTS === '1') {
    assert.deepEqual(a.enums.get('gnc_java_game_modes'), ['survival', 'creative', 'adventure', 'spectator'])
    for (const name of ['gamemode', 'defaultgamemode']) {
      const definition = a.latest.command_data.find(data => {
        const aliases = a.latest.enums[data.alias]
        return data.name === name || aliases && aliases.values.some(index => a.latest.enum_values[index] === name)
      })
      assert(definition, 'OP test client must receive /' + name)
      assert(definition.overloads.some(overload => overload.parameters.some(parameter =>
        parameter.parameter_name === 'gamemode' && parameter.enum_type === 'soft_enum')))
    }
    checks.push('Java game mode choices are received for gamemode and defaultgamemode')
    await until('Java damage types become native argument SoftEnums', () => {
      const choices = nativeValues(a, 'damage', 'damageType')
      return choices.some(v => /(^|:)fall$/.test(v)) && choices.some(v => /(^|:)generic$/.test(v))
    })
    const damage = a.latest.command_data.find(data => data.name === 'damage')
    assert(damage.overloads.some(o => o.parameters[0].value_type === 'target' && o.parameters[1].value_type === 'float'))
    const domains = damage.overloads.map(o => o.parameters.find(p => p.parameter_name === 'damageType'))
    assert(domains.every(p => p.enum_type === 'soft_enum'))
    assert.equal(new Set(domains.map(p => p.value_type)).size, 1)
    assert.equal(domains.filter(p => p.options.unknown2 === 0).length, 1)
    assert.equal(a.latest.dynamic_enums.filter(e => e.name.startsWith('damage_arg5_')).length, 1)
    checks.push('Damage branches share one enum and one visible completion source')
    await until('A receives player-specific Java argument choices', () => nativeValues(a, 'ncf_native', 'choice').includes('NCTestA_base'))
  }
  const b = connect('NCTestB')
  await prepare(b)
  await until('B receives own home', () => b.spawned && values(b, ['delete']).includes('NCTestB_base'))
  assert(!values(a, ['delete']).includes('NCTestB_base'))
  assert(!values(b, ['delete']).includes('NCTestA_base'))
  checks.push('Player-specific candidates are isolated')
  checks.push('Initial and updated command packets avoid the incompatible server help definition')
  if (process.env.BEDROCK_NATIVE_ARGUMENTS === '1') {
    await until('B receives isolated Java argument choices', () => nativeValues(b, 'ncf_native', 'choice').includes('NCTestB_base'))
    assert(!nativeValues(a, 'ncf_native', 'choice').includes('NCTestB_base'))
    assert(!nativeValues(b, 'ncf_native', 'choice').includes('NCTestA_base'))
  }
  const mark = a.packets.length
  await command(a, '/ncf set added_home')
  await until('Add refreshes the generic argument candidates', () => values(a, ['delete']).includes('added_home'))
  if (process.env.BEDROCK_NATIVE_ARGUMENTS === '1')
    await until('Changed Java argument choices are received', () => nativeValues(a, 'ncf_native', 'choice').includes('added_home'))
  assert(!values(b, ['delete']).includes('added_home'))
  // Other command domains may split/merge concurrently as players join or a
  // completer leaves cooldown. Those structural changes require a full resend.
  checks.push(a.packets.slice(mark).some(p => p.type === 'available_commands')
    ? 'Candidate updates survive concurrent command structure changes without leaking to B'
    : 'Candidate-only change avoids full command resend and does not leak to B')
  await command(a, '/ncf delete added_home')
  await until('Delete removes home via SoftEnum REPLACE', () => !values(a, ['delete']).includes('added_home'))
  if (process.env.BEDROCK_NATIVE_ARGUMENTS === '1')
    await until('Deleted Java argument choice is withdrawn', () => !nativeValues(a, 'ncf_native', 'choice').includes('added_home'))
  const before = a.availableCount
  await command(a, '/ncf delete NCTestA_base')
  await until('Empty branch rebuilds AvailableCommands', () => a.availableCount > before && !a.enums.has(enumName(['delete'])))
  assert(values(b, ['delete']).includes('NCTestB_base'))
  checks.push('Removing A branch preserves B candidates')
  await command(a, '/ncf set restored_home')
  await until('Restored branch rebuilds AvailableCommands', () => values(a, ['delete']).includes('restored_home'))
  assert(a.packets.some(p => p.type === 'update_soft_enum' && p.packet.action_type === 'update' &&
    (p.packet.enum_type === enumName(['delete']) || p.packet.enum_type.startsWith('nativecompletionfixture__arg'))))
  checks.push('Dynamic fixture candidates also use the SoftEnum REPLACE path')
  if (process.env.BEDROCK_SELECTOR_CONVERSION === '1') {
    // The server must have selector-auto-convert: true; NCTestA must be OP.
    const selectorCases = [
      ['/execute if entity @e[rm=0,r=20,c=1] run say GNC_BEDROCK_RADIUS', 'GNC_BEDROCK_RADIUS'],
      ['/execute if entity @a[m=creative,lm=0,l=100] run say GNC_BEDROCK_MODE', 'GNC_BEDROCK_MODE'],
      ['/execute if entity @a[rxm=-90,rx=90,rym=-180,ry=180] run say GNC_BEDROCK_ROTATION', 'GNC_BEDROCK_ROTATION'],
      ['/execute if entity @e[c=-1] run say GNC_BEDROCK_FURTHEST', 'GNC_BEDROCK_FURTHEST'],
      ['/execute if entity @e[distance=..20,limit=1] run say GNC_JAVA_PRESERVED', 'GNC_JAVA_PRESERVED'],
      ['/say "@e[r=5]"', '@e[r=5]']
    ]
    for (const [source, expected] of selectorCases) {
      const start = a.packets.length
      await command(a, source)
      await until('Selector conversion: ' + expected, () => a.packets.slice(start).some(
        record => record.type === 'text' && record.packet.message.includes(expected)), 15000)
    }
  }
}
main().then(() => { console.log(`PASS: ${checks.length} checks`); process.exitCode = 0 })
  .catch(error => { console.error(error); process.exitCode = 1 })
  .finally(async () => {
    const output = path.join(__dirname, 'results')
    fs.mkdirSync(output, { recursive: true })
    fs.writeFileSync(path.join(output, 'latest.json'), JSON.stringify({ time: new Date().toISOString(), host, port, version, node: process.version, library: require('bedrock-protocol/package.json').version, checks, success: process.exitCode === 0, clients: clients.map(({ username, packets }) => ({ username, packets })) }, (_, v) => typeof v === 'bigint' ? v.toString() : v, 2))
    for (const state of clients) { state.closing = true; state.client.close() }
    await pause(500)
    process.exit(process.exitCode)
  })
