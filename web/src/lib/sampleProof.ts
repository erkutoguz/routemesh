export function sampleProofImage(kind: "PHOTO" | "SIGNATURE"): Promise<Blob> {
  const canvas = document.createElement("canvas");
  canvas.width = 320;
  canvas.height = kind === "PHOTO" ? 240 : 120;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    return Promise.reject(new Error("Canvas is not available in this browser"));
  }
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.strokeStyle = "#0f172a";
  ctx.lineWidth = 3;
  if (kind === "PHOTO") {
    ctx.fillStyle = "#e2e8f0";
    ctx.fillRect(40, 60, 240, 130);
    ctx.strokeRect(40, 60, 240, 130);
    ctx.beginPath();
    ctx.moveTo(40, 60);
    ctx.lineTo(160, 125);
    ctx.lineTo(280, 60);
    ctx.stroke();
    ctx.fillStyle = "#0f172a";
    ctx.font = "16px sans-serif";
    ctx.fillText("Sample delivery photo", 80, 30);
  } else {
    ctx.beginPath();
    ctx.moveTo(30, 80);
    ctx.bezierCurveTo(70, 10, 100, 110, 140, 50);
    ctx.bezierCurveTo(170, 10, 200, 100, 290, 40);
    ctx.stroke();
  }
  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("Could not create the sample image"))), "image/png"),
  );
}

export function isOtp(value: string): boolean {
  return /^[0-9]{4,8}$/.test(value);
}
