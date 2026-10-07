# FL-Combat — Referência Rápida da API

**Package:** `com.lucasalfare.flcombat`

## 1. Identidade

### `Agent`

* `id: String`

Identidade estável de um participante. Não possui estado de combate.

### `Attribute`

* `id`
* `baseValue`
* `effectiveValue`

Snapshot imutável de um atributo.

### `Modifier`

* `id`
* `value`

Alteração opaca de um atributo. O significado do `id` pertence ao `ModifierComposer`.

---

# 2. Itens e efeitos

### `Item`

* `id`
* `modifiers: Map<String, List<Modifier>>`

Fonte de modificadores associada a um agente.

Adicionar outro item com o mesmo `id` substitui o anterior.

### `Effect`

* `id`
* `modifiers`
* `durationTicks: Int?`
* `onApply`
* `onRemove`

Efeito temporário ou permanente.

* `null` → permanente;
* `0` → expira no primeiro `tick`;
* positivo → duração inicial.

Mesmo `id` → efeito anterior é removido e substituído.

---

# 3. Composição de atributos

### `ModifierComposer`

`compose(baseValue, modifiers): Int`

Define como modificadores produzem o valor efetivo.

### `AdditiveThenPercentageComposer`

Implementação padrão.

IDs reconhecidos:

* `ADDITIVE = "additive"`
* `PERCENTAGE = "percentage"`

Fórmula:

`floor((base + additive) × (100 + percentage) / 100)`

Outros modificadores são ignorados.

---

# 4. Estado

### `CombatState`

Estado mutável de um combate.

Principais APIs:

* `register(agent)`
* `contains(agent)`
* `agents()`
* `setAttribute(...)`
* `attribute(...)`
* `attributes(...)`
* `removeAttribute(...)`
* `addModifier(...)`
* `removeModifier(...)`
* `modifiers(...)`
* `addItem(...)`
* `removeItem(...)`
* `items(...)`
* `applyEffect(...)`
* `removeEffect(...)`
* `effects(...)`
* `effect(...)`
* `remainingTicks(...)`
* `tick(...)`
* `markDefeated(...)`
* `isDefeated(...)`
* `drainEvents()`

Entidades precisam ser registradas para as principais operações de mutação.

O estado é síncrono e **não thread-safe**.

---

# 5. Aleatoriedade

### `RandomSource`

`nextInt(fromInclusive, toInclusive): Int`

Fornece um inteiro dentro de intervalo inclusivo.

### `DefaultRandomSource`

Implementação usando `Random.Default`.

### `DeterministicRandomSource`

Fornece uma sequência pré-configurada.

Útil para testes reproduzíveis.

---

# 6. Dano

### `DamageRange`

* `min`
* `max`

Faixa inclusiva de dano possível.

`min >= 0` e `min <= max`.

### `DamageContext`

* `attacker`
* `target`
* `state`

Contexto para cálculo do dano.

### `DamageFormula`

`calculate(context): DamageRange`

Define **qual dano é possível**, sem realizar a rolagem.

### `FixedDamageFormula`

Sempre produz uma faixa fixa:

`damage..damage`

---

# 7. Rolagem

### `DamageRoll`

`roll(range): Int`

Escolhe o dano concreto dentro de uma `DamageRange`.

### `DeterministicDamageRoll`

Sempre retorna `range.min`.

### `UniformDamageRoll`

Usa `RandomSource` para escolher uniformemente dentro da faixa.

---

# 8. Mitigação

### `MitigationContext`

* `origin`
* `target`
* `damageType`
* `state`

### `Mitigation`

`mitigate(rolledDamage, context): Int`

Transforma o dano rolado antes da aplicação.

### `NoMitigation`

Não reduz o dano.

### `FixedReductionMitigation`

Subtrai quantidade fixa, nunca abaixo de zero.

### `PercentageReductionMitigation`

Aplica redução percentual, com resultado nunca negativo.

### `MitigationChain`

Executa várias mitigações sequencialmente na ordem fornecida.

Cadeia vazia equivale a nenhuma mitigação.

---

# 9. Aplicação

### `DamageApplicationContext`

* `origin`
* `target`
* `damageType`
* `state`

### `DamageApplication`

`apply(mitigatedDamage, context): Int`

É o ponto onde o dano pode alterar efetivamente o `CombatState`.

O retorno é o dano realmente aplicado e pode ser menor que o dano mitigado.

---

# 10. Resultado do dano

### `DamageResult`

* `origin`
* `target`
* `damageType`
* `rolledDamage`
* `mitigatedDamage`
* `appliedDamage`

Distingue claramente:

`rolado → mitigado → aplicado`

Todos os valores são não negativos.

---

# 11. Ações

### `CombatAction`

Interface com:

* `attacker`
* `target`
* `damageType`

Permite que a aplicação defina seus próprios tipos de ação.

### `Attack`

Implementação padrão simples de `CombatAction`.

---

# 12. Acerto

### `HitResolution`

`resolve(action, state): Boolean`

Decide se a ação acertou.

Não deve modificar o estado.

### `AlwaysHit`

Sempre retorna `true`.

---

# 13. Resolução

### `CombatResolver`

Recebe:

* `HitResolution`
* `DamageFormula`
* `DamageRoll`
* `Mitigation`
* `DamageApplication`

### `resolve(action, state): CombatResult`

Pipeline:

`Action → Hit → Formula → Roll → Mitigation → Application`

Miss:

* `hit = false`;
* `damageResult = null`;
* gera `AttackMissed`.

Hit:

* gera `AttackPerformed`;
* calcula e aplica dano;
* produz `DamageResult`;
* coleta eventos gerados durante a aplicação.

---

# 14. Resultado do combate

### `CombatResult`

* `action`
* `hit`
* `damageResult`
* `state`
* `events`

Em caso de miss, `damageResult == null`.

`state` é a mesma instância fornecida ao resolver.

---

# 15. Eventos

### `CombatEvent`

`sealed interface`

Eventos disponíveis:

* `AttackPerformed`
* `AttackMissed`
* `DamageProduced`
* `DamageApplied`
* `EffectApplied`
* `EffectRemoved`
* `Defeat`

Eventos carregam os dados necessários para o consumidor reagir sem o core conhecer UI ou regras específicas do jogo.

---

# 16. Modelo mental rápido

### Participante

`Agent`

### Estado

`CombatState`

### Atributos

`Attribute + Modifier + ModifierComposer`

### Ação

`CombatAction`

### Acerto

`HitResolution`

### Dano possível

`DamageFormula → DamageRange`

### Dano concreto

`DamageRoll`

### Dano após defesa

`Mitigation`

### Dano efetivamente aplicado

`DamageApplication`

### Resultado

`DamageResult → CombatResult`

### Observação

`CombatEvent`

### Regra central

**O jogo fornece o significado. O core fornece a estrutura do combate.**
