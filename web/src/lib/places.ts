export interface Place {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
}

export const PLACES: Place[] = [
  { id: "sultanahmet", label: "Fatih — Sultanahmet (depot)", latitude: 41.0082, longitude: 28.9784 },
  { id: "taksim", label: "Beyoğlu — Taksim", latitude: 41.037, longitude: 28.985 },
  { id: "besiktas", label: "Beşiktaş", latitude: 41.043, longitude: 29.0094 },
  { id: "sisli", label: "Şişli", latitude: 41.0602, longitude: 28.9877 },
  { id: "sariyer", label: "Sarıyer", latitude: 41.167, longitude: 29.057 },
  { id: "uskudar", label: "Üsküdar", latitude: 41.0226, longitude: 29.0153 },
  { id: "kadikoy", label: "Kadıköy", latitude: 40.9906, longitude: 29.029 },
  { id: "maltepe", label: "Maltepe", latitude: 40.935, longitude: 29.131 },
];

export const CUSTOM_PLACE = "custom";

export function parseCoordinate(value: string, min: number, max: number): number | null {
  const parsed = Number(value.trim().replace(",", "."));
  if (value.trim() === "" || !Number.isFinite(parsed) || parsed < min || parsed > max) {
    return null;
  }
  return parsed;
}

export function isValidPhone(value: string): boolean {
  return /^\+?[0-9]{10,15}$/.test(value);
}
