# Catalog sources

The catalog uses the three device files from the vendor loss library.
Each current rating uses the condition shown in the table.
The ST value applies to one MOSFET in the inverter module.

| Device | Continuous current condition | On-state value | Total gate charge | Junction-to-case resistance |
| --- | --- | --- | --- | --- |
| C3M0065090D | 36 A at 25 °C case temperature | 65 mΩ typical at 20 A | 33 nC typical | 1.0 °C/W maximum |
| IKW40N65H5 | 46 A at 100 °C case temperature | 1.65 V typical at 40 A | 95 nC typical | 0.60 °C/W maximum |
| STIPQ5M60T-HZ | 5 A per MOSFET | 1.0 Ω maximum at 2.5 A | Unavailable for the integrated gate drive | 9.2 °C/W per MOSFET |

The example assumes 5 °C/W from case to ambient for each device. Use the value for the actual cooling path.
Enter supplier quotes in the price column. The seed catalog leaves prices blank.
Loss estimates use the available vendor operating points. The library sidecars give the conditions for the curve samples.

## Sources

- [Wolfspeed C3M0065090D data sheet](https://assets.wolfspeed.com/uploads/2024/01/Wolfspeed_C3M0065090D_data_sheet.pdf)
- [Infineon IKW40N65H5 data sheet](https://www.infineon.com/assets/row/public/documents/24/49/infineon-ikx40n65h5-datasheet-en.pdf)
- [STMicroelectronics STIPQ5M60T-HZ data sheet](https://www.st.com/resource/en/datasheet/stipq5m60t-hz.pdf)
