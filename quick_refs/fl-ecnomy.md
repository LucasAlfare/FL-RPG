# FL-Economy — Referência Rápida da API

**Package:** `com.lucasalfare.fleconomy`

## 1. Identificadores

### `AccountId`

`value: String`

ID não vazio de uma conta.

### `TransactionId`

`value: String`

ID não vazio de uma transação registrada no ledger.

### `OperationId`

`value: String`

ID lógico de uma operação. Uma mesma operação pode possuir várias `Transaction`.

### `LoanId`

`value: String`

ID não vazio de um empréstimo.

---

# 2. Valores econômicos

### `Quantity`

Quantidade decimal não negativa baseada em `BigDecimal`.

Principais APIs:

* `Quantity.ZERO`
* `Quantity.of(BigDecimal)`
* `Quantity.of(Long)`
* `Quantity.of(String)`
* `+`
* `-`
* `compareTo`
* `isZero()`
* `isPositive()`

Subtração que produziria valor negativo lança `IllegalArgumentException`.

A igualdade ignora zeros finais do `BigDecimal`.

---

### `Currency`

`code: String`

Identificador não vazio de moeda.

A biblioteca trata moedas como identificadores opacos. Não existe conversão automática entre moedas.

---

### `Money`

* `quantity: Quantity`
* `currency: Currency`

Valor monetário não negativo.

Operações:

* `+`
* `-`
* `compareTo`
* `isZero()`
* `isPositive()`

`+`, `-` e comparação exigem a mesma moeda.

Fábricas:

* `Money.of(BigDecimal, Currency)`
* `Money.of(Long, Currency)`
* `Money.of(String, Currency)`
* `Money.zero(Currency)`

---

### `ResourceRef`

* `type: String`
* `id: String`

Identificador de um recurso não monetário. Ambos não podem ser vazios.

---

### `ResourceAmount`

* `resource: ResourceRef`
* `quantity: Quantity`

Quantidade de um recurso específico.

Operações:

* `+`
* `-`
* `compareTo`
* `isZero()`
* `isPositive()`

As operações exigem o mesmo `ResourceRef`.

Fábricas:

* `of(type, id, BigDecimal)`
* `of(type, id, Long)`
* `of(type, id, String)`
* `of(resource, BigDecimal)`
* `of(resource, Long)`
* `of(resource, String)`
* `zero(resource)`

---

# 3. `EconomicValue`

`sealed class`

Representa genericamente um valor econômico.

### `EconomicValue.Monetary`

`money: Money`

### `EconomicValue.Resource`

`amount: ResourceAmount`

Fábricas:

* `EconomicValue.of(Money)`
* `EconomicValue.of(ResourceAmount)`

---

# 4. `Balance`

Snapshot imutável de uma conta.

* `moneys: Map<Currency, Quantity>`
* `resources: Map<ResourceRef, Quantity>`

APIs:

* `moneyOf(currency): Quantity`
* `resourceOf(resource): Quantity`
* `isEmpty()`

Ausência no mapa significa quantidade zero.

`Balance.EMPTY` representa saldo vazio.

---

# 5. `Account`

Conta econômica pertencente a uma `Economy`.

`id: AccountId`

O construtor é `internal`: contas devem ser criadas por `Economy.createAccount`.

### API pública

`balance(): Balance`

Retorna snapshot imutável do saldo atual.

A conta não expõe operações públicas de alteração de saldo. Toda mutação ocorre por operações da `Economy`.

---

# 6. Movimentações e operações

### `Movement`

Movimento elementar de valor:

* `from: AccountId?`
* `to: AccountId?`
* `value: EconomicValue`

Formas:

* `from != null`, `to != null` → transferência;
* `from == null`, `to != null` → emissão;
* `from != null`, `to == null` → retirada.

Não pode ter ambos nulos.

---

### `Transfer`

* `from: AccountId`
* `to: AccountId`
* `value: EconomicValue`

Descrição de uma transferência entre contas distintas.

---

### `Exchange`

`transfers: List<Transfer>`

Exchange precisa conter pelo menos uma transferência.

Todos os transfers são aplicados atomicamente.

---

### `Charge`

* `from`
* `to`
* `value`

Carga adicional anexada a `transfer` ou `exchange`.

Pode representar taxa, imposto, comissão, penalidade etc.

---

### `Transaction`

Registro imutável de uma operação efetivamente commitada.

* `id: TransactionId`
* `operationId: OperationId`
* `movements: List<Movement>`
* `timestamp: Instant`

Depois de registrada no ledger, não é modificada.

---

# 7. Empréstimos

### `LoanState`

* `OPEN`
* `PAID`
* `DEFAULTED`

### `Loan`

* `id`
* `creditor`
* `debtor`
* `principal: Money`
* `interest: Money`
* `dueDate: Instant`
* `paid: Money`
* `state: LoanState`

APIs:

`amountDue(): Money`

Calcula:

`principal + interest - paid`

`isFullyPaid(): Boolean`

Retorna `true` quando `paid >= principal + interest`.

---

# 8. Eventos e observabilidade

A `Economy` expõe um sistema de eventos para que aplicações possam observar mudanças econômicas sem precisar consultar o
estado repetidamente.

Os eventos representam **fatos já ocorridos**. Eles somente são emitidos depois que a alteração correspondente foi
efetivamente commitada.

Os eventos são entregues de forma síncrona na thread que realizou a operação, mas somente depois da liberação do write
lock.

Falhas em listeners não desfazem nem invalidam uma operação econômica já commitada.

---

## `BalanceChange`

Snapshot imutável de uma alteração de saldo.

* `accountId: AccountId`
* `previousBalance: Balance`
* `newBalance: Balance`

Representa a diferença entre o saldo anterior e o novo saldo de uma conta afetada por uma `Transaction`.

---

## `EconomyEvent`

`sealed interface`

Tipo base de todos os eventos emitidos por uma `Economy`.

### `EconomyEvent.AccountCreated`

Emitido quando uma conta é criada.

* `accountId: AccountId`

---

### `EconomyEvent.TransactionCommitted`

Emitido quando uma `Transaction` é efetivamente registrada no ledger.

* `transaction: Transaction`
* `balanceChanges: List<BalanceChange>`

`balanceChanges` contém os snapshots anterior/novo das contas afetadas pela transação.

Esse é o principal evento para observar movimentações econômicas.

Ele cobre:

* emissão;
* retirada;
* transferência;
* exchange;
* charges;
* transferência de principal de empréstimo;
* pagamentos de empréstimos.

---

### `EconomyEvent.LoanCreated`

Emitido quando um novo empréstimo é criado com sucesso.

* `loan: Loan`
* `transaction: Transaction`

`transaction` é a transação que transferiu o principal do creditor para o debtor.

Durante `createLoan`, os eventos são emitidos na ordem:

1. `TransactionCommitted`
2. `LoanCreated`

---

### `EconomyEvent.LoanPaid`

Emitido quando um pagamento de empréstimo é efetivamente realizado.

* `previousLoan: Loan`
* `loan: Loan`
* `transaction: Transaction`

`previousLoan` representa o empréstimo imediatamente antes do pagamento.

`loan` representa o estado imediatamente depois do pagamento.

`transaction` representa a transferência do pagamento entre debtor e creditor.

Durante `payLoan`, os eventos são emitidos na ordem:

1. `TransactionCommitted`
2. `LoanPaid`

Se o pagamento quitar completamente o empréstimo, `loan.state` será `PAID`.

---

### `EconomyEvent.LoanDefaulted`

Emitido quando um empréstimo `OPEN` é marcado como `DEFAULTED`.

* `previousLoan: Loan`
* `loan: Loan`

Não há alteração de saldo nem `Transaction` associada ao default.

---

## `EconomyEventListener`

Observer funcional para receber eventos.

API:

`onEvent(event: EconomyEvent)`

Pode ser usado para observar todos os eventos de uma `Economy`.

---

## `EconomySubscription`

Handle retornado por uma inscrição de eventos.

### `unsubscribe()`

Remove a inscrição.

É idempotente: chamar mais de uma vez não produz efeito adicional.

### `isActive(): Boolean`

Retorna `true` enquanto a inscrição estiver registrada.

---

# 9. `Economy`

Núcleo da biblioteca.

É **thread-safe** e todas as operações mutáveis são atômicas para a instância inteira.

Leitores podem executar concorrentemente; escritores usam acesso exclusivo.

Cada `Economy` é totalmente independente das demais.

---

## Observação de eventos

`subscribe(listener: EconomyEventListener): EconomySubscription`

Registra um listener para todos os eventos da `Economy`.

Os eventos são entregues na ordem de registro dos listeners.

Exemplo conceitual:

`economy.subscribe { event -> ... }`

---

`subscribe<E : EconomyEvent>(listener: (E) -> Unit): EconomySubscription`

Registra um listener somente para um tipo específico de `EconomyEvent`.

Permite observar diretamente, por exemplo:

* `EconomyEvent.AccountCreated`
* `EconomyEvent.TransactionCommitted`
* `EconomyEvent.LoanCreated`
* `EconomyEvent.LoanPaid`
* `EconomyEvent.LoanDefaulted`

O retorno é um `EconomySubscription`, que pode ser usado para cancelar a observação.

---

## Contas

`createAccount(id): Account`

Cria conta vazia.

ID duplicado → `IllegalArgumentException`.

Em caso de sucesso, emite:

`EconomyEvent.AccountCreated`

`getAccount(id): Account?`

`accountExists(id): Boolean`

`accountIds(): Set<AccountId>`

`accounts(): List<AccountId>`

`balanceOf(id): Balance`

Conta inexistente → `IllegalArgumentException`.

`balances(): Map<AccountId, Balance>`

Snapshot dos saldos de todas as contas.

---

## Moedas e recursos

`currencies(): Set<Currency>`

Todas as moedas presentes em alguma conta.

`currenciesOf(id): Set<Currency>`

Moedas de uma conta específica.

Conta inexistente → `IllegalArgumentException`.

`resources(): Set<ResourceRef>`

Todos os recursos presentes em alguma conta.

`resourcesOf(id): Set<ResourceRef>`

Recursos de uma conta específica.

Conta inexistente → `IllegalArgumentException`.

---

# 10. Ledger

O ledger é append-only.

### `ledgerHistory(): List<Transaction>`

Histórico completo.

### `ledgerSize(): Int`

Número de transações commitadas.

### `getTransaction(id): Transaction?`

Busca por ID.

### `transactionsByOperation(operationId): List<Transaction>`

Retorna as transações pertencentes à mesma `OperationId`.

Toda `Transaction` efetivamente commitada gera:

`EconomyEvent.TransactionCommitted`

---

# 11. Emissão e retirada

### `issue(to, value): Transaction`

Cria valor:

`null → account`

A quantidade precisa ser não negativa.

Gera automaticamente:

* `OperationId`
* `TransactionId`
* timestamp com `Instant.now()`

É atômico.

Após o commit, emite:

`EconomyEvent.TransactionCommitted`

---

### `retire(from, value): Transaction`

Destrói valor:

`account → null`

Exige saldo/recurso suficiente.

É atômico.

Após o commit, emite:

`EconomyEvent.TransactionCommitted`

---

# 12. Transferência

### `transfer(transfer, charges = emptyList()): Transaction`

Executa:

* transferência principal;
* todas as charges.

Tudo pertence à **mesma `Transaction`** e é aplicado atomicamente.

Valida:

* contas existentes;
* contas de origem e destino diferentes;
* valores não negativos;
* saldo suficiente para todos os débitos.

Falha em qualquer validação → nenhuma alteração de saldo e nenhum evento de sucesso.

Após o commit, emite:

`EconomyEvent.TransactionCommitted`

---

# 13. Exchange

### `exchange(exchange, charges = emptyList()): Transaction`

Executa múltiplas transferências e charges como uma única operação atômica.

Todas as movimentações são validadas antes da aplicação.

Falha em qualquer etapa → nenhum saldo é alterado e nenhum evento de sucesso é emitido.

A ordem dos `Movement` na `Transaction` é:

1. transfers do `Exchange`;
2. charges.

Após o commit, emite:

`EconomyEvent.TransactionCommitted`

---

# 14. Empréstimos

### `createLoan(...)`

Parâmetros:

* `creditor`
* `debtor`
* `principal: Money`
* `interest: Money`
* `dueDate`

Regras:

* creditor ≠ debtor;
* ambas as contas existem;
* principal e interest usam a mesma moeda;
* principal > 0;
* interest ≥ 0.

Ao criar:

1. principal é transferido de creditor para debtor;
2. loan é criado como `OPEN`;
3. `paid` começa em zero.

A transferência do principal gera uma `Transaction` normal no ledger.

Eventos emitidos, nesta ordem:

1. `EconomyEvent.TransactionCommitted`
2. `EconomyEvent.LoanCreated`

`LoanCreated.transaction` referencia a `Transaction` responsável pela transferência do principal.

---

### `getLoan(id): Loan?`

### `loanExists(id): Boolean`

### `loansOf(accountId): List<Loan>`

Retorna empréstimos onde a conta é creditor ou debtor.

### `allLoans(): List<Loan>`

Todos os empréstimos.

### `loansByState(state): List<Loan>`

Filtra por estado.

---

### `payLoan(loanId, amount): Loan`

Pagamento de empréstimo aberto.

O pagamento é uma transferência normal:

`debtor → creditor`

Regras:

* loan existe;
* loan está `OPEN`;
* moeda coincide;
* valor > 0;
* pagamento não pode exceder `amountDue()`.

Depois do pagamento:

* se totalmente quitado → `PAID`;
* caso contrário → permanece `OPEN`.

Eventos emitidos, nesta ordem:

1. `EconomyEvent.TransactionCommitted`
2. `EconomyEvent.LoanPaid`

`LoanPaid` fornece tanto o estado anterior quanto o estado atualizado do empréstimo.

---

### `defaultLoan(loanId): Loan`

Marca empréstimo `OPEN` como `DEFAULTED`.

Não altera saldos.

Não cria `Transaction`.

Após a alteração, emite:

`EconomyEvent.LoanDefaulted`

As consequências econômicas de um default ficam por conta da aplicação.

---

# 15. Juros

### `Interest`

Objeto utilitário puro.

Não modifica contas, loans ou ledger.

Também não produz eventos.

### `Interest.simple(principal, rate, periods): Money`

Fórmula:

`principal × rate × periods`

Regras:

* `periods >= 0`;
* `rate` não é negativo;
* resultado usa a mesma moeda do principal.

Retorna zero quando:

* periods = 0;
* principal = 0;
* rate = 0.

### `Interest.compound(principal, rate, periods): Money`

Fórmula:

`principal × ((1 + rate)^periods − 1)`

Regras:

* `periods >= 0`;
* rate não é negativo;
* mesma moeda do principal.

Retorna zero para `periods = 0` ou principal zero.

---

# 16. Atomicidade e eventos

Toda operação mutável da `Economy` é atômica em relação à instância inteira.

O mecanismo:

1. valida todas as movimentações;
2. calcula saldos projetados;
3. altera as contas;
4. adiciona a `Transaction` ao ledger;
5. atualiza outras entidades necessárias, como `Loan`;
6. libera o write lock;
7. somente então publica os eventos correspondentes.

Portanto:

* nenhum evento de sucesso é emitido para uma operação que falhou;
* listeners nunca observam um estado parcialmente commitado;
* uma exceção em um listener não desfaz a operação econômica;
* eventos são fatos posteriores à mudança, não parte da transação econômica.

---

# 17. Modelo mental rápido

### Valor

`Quantity → Money / ResourceAmount → EconomicValue`

### Conta

`Account → Balance`

### Movimento

`Movement`

### Operação

`Transfer / Exchange / Charge`

### Registro

`Transaction → Ledger`

### Observabilidade

`Economy → EconomyEvent → EconomyEventListener`

### Mudança de saldo

`BalanceChange`

### Inscrição

`subscribe() → EconomySubscription`

### Crédito

`issue`

### Destruição

`retire`

### Transferência

`transfer`

### Operação multi-leg

`exchange`

### Crédito parcelado / obrigação

`Loan`

### Cálculo financeiro

`Interest`

### Regra central

**A aplicação define o significado econômico; `Economy` garante armazenamento, validação, atomicidade, concorrência,
histórico e observabilidade.**
