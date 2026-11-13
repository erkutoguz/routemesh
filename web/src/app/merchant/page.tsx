import { redirect } from "next/navigation";

export default function MerchantHome() {
  redirect("/merchant/shipments");
}
