# GymRest — Samsung Watch 4
Temporizador de descanso inteligente com detecção automática de exercício.

---

## Funcionalidades

| Recurso | Detalhe |
|---|---|
| Detecção automática | Acelerômetro + Giroscópio + FC detectam início/fim de série |
| Classificação de exercício | 14 exercícios reconhecidos por padrão de movimento |
| Timer de descanso | Conta regressiva com anel circular animado |
| Gestos | Torção de pulso (pausar), fling (pular), duplo toque (série OK) |
| Modo Ambient | Tela sempre visível com consumo mínimo de bateria |
| Histórico | Banco Room com séries e sessões (últimos 30 dias) |
| Serviço em background | Sensores continuam quando tela apaga |

---

## Estrutura do Projeto

```
GymRest/
└── app/src/main/
    ├── java/com/gymrest/
    │   ├── MainActivity.kt          ← Activity principal + fusão de sensores
    │   ├── ExerciseClassifier.kt    ← Classificação por regras (substituível por TFLite)
    │   ├── RestRingView.kt          ← View customizada Canvas (anel animado)
    │   ├── SensorForegroundService.kt ← Mantém sensores vivos com tela off
    │   └── Database.kt             ← Room DB (SetRecord, SessionRecord)
    ├── res/layout/
    │   └── activity_main.xml
    └── AndroidManifest.xml
```

---

## Pré-requisitos

- Android Studio Hedgehog ou superior
- Wear OS SDK 28+
- Samsung Health SDK (.aar) → baixar em:
  https://developer.samsung.com/health/android/data/guide/health-data-store.html
  → copiar para `app/libs/`
- Samsung Galaxy Watch 4 com modo desenvolvedor ativo

---

## Como rodar

```bash
# 1. Clone / abra no Android Studio
# 2. Baixe o Samsung Health SDK e cole em app/libs/
# 3. Ative modo desenvolvedor no Watch 4:
#    Configurações → Sobre relógio → Info de software → toque 5x em "Versão do software"
# 4. Conecte via ADB over Bluetooth ou Wi-Fi:
adb connect <IP-do-relógio>:5555
# 5. Run → selecione o dispositivo Watch 4
```

---

## Limiares dos sensores (ajustáveis em MainActivity.kt)

| Constante | Valor padrão | Significado |
|---|---|---|
| `ACCEL_EXERCISE_THRESHOLD` | 1.4 g | Magnitude mínima para detectar série ativa |
| `ACCEL_STOP_THRESHOLD` | 0.25 g | Abaixo disso considera que parou |
| `GYRO_TWIST_THRESHOLD` | 4.5 rad/s | Torção que aciona pausa/retomada |
| `HR_SKIP_THRESHOLD` | 130 bpm | FC alta durante descanso = alerta |
| `STOP_CONFIRM_MS` | 1800 ms | Tempo parado para confirmar fim de série |

---

## Gestos suportados

| Gesto | Ação |
|---|---|
| Duplo toque na tela | Série concluída (ou inicia série manualmente) |
| Fling para cima (swipe rápido) | Pula o descanso |
| Torção do pulso (giroscópio) | Pausa / retoma o timer |
| Botão central (crown) | Resetar |

---

## Publicar na Galaxy Store

1. Gere o APK assinado:
   `Build → Generate Signed Bundle / APK → APK`
2. Acesse: https://seller.samsungapps.com
3. Crie novo app → categoria "Health & Fitness"
4. Faça upload do APK + screenshots da tela redonda (450×450 px)
5. Preencha descrição, selecione "Galaxy Watch 4" como dispositivo alvo
6. Submeta para revisão (~3 dias úteis)

---

## Próximos passos sugeridos

- [ ] Substituir `ExerciseClassifier` por modelo TFLite treinado com dados reais
- [ ] Integrar Samsung Health para gravar workout oficial no app Saúde
- [ ] Tile (widget de atalho) para iniciar sessão direto da tela inicial
- [ ] Complication para mostrar séries do dia no mostrador do relógio
- [ ] Sincronização com app Android companion via DataLayer API
