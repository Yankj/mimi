import spec from '../../../../../docs/design/prompts/cartoon-ecopy/prompt-pack/spec.json' with { type: 'json' };
import { createSupabaseTokenVerifier } from '../_shared/core/src/supabase-auth.mjs';
import { createCartoonECopyService, handleCartoonECopy } from '../_shared/cartoon-ecopy/service.mjs';

// Local maintenance entry. Do not deploy from this tree.
const env = (name: string) => Deno.env.get(name) ?? undefined;
const service = createCartoonECopyService({
  spec,
  env,
  verifyUser: createSupabaseTokenVerifier({ projectUrl: env('SUPABASE_URL') }),
});

Deno.serve((request) => handleCartoonECopy(service, request));
