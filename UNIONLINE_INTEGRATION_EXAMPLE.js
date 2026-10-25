// Example only: call this from a user-initiated Unionline print button.
// The app must already be installed and the target printer saved in Union Bridge.
function openUnionBridge(payload) {
  const json = JSON.stringify(payload);
  const bytes = new TextEncoder().encode(json);
  let binary = "";
  bytes.forEach(b => binary += String.fromCharCode(b));
  const base64url = btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  window.location.href = "unionbridge://print?payload=" + encodeURIComponent(base64url);
}

function printLabel(product) {
  openUnionBridge({
    type: "label",
    copies: 1,
    label: {
      company: "Baumarket",
      name: product.name,
      sku: product.sku,
      price: product.price,
      barcode: product.barcode || product.sku,
      widthMm: 40,
      heightMm: 30,
      language: "tspl2"
    }
  });
}

function printReceipt(receipt) {
  openUnionBridge({
    type: "receipt",
    copies: 1,
    receipt: {
      company: "Baumarket",
      title: "FATURE",
      number: receipt.number,
      date: receipt.date,
      items: receipt.items,
      subtotal: receipt.subtotal,
      total: receipt.total,
      footer: "Faleminderit!"
    }
  });
}
